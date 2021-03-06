import sbt._
import Project.Setting
import Keys._

import GenTypeClass._

import java.awt.Desktop

import scala.collection.immutable.IndexedSeq

import sbtrelease._
import sbtrelease.ReleasePlugin.autoImport._
import sbtrelease.ReleaseStateTransformations._
import sbtrelease.Utilities._

import com.typesafe.sbt.pgp.PgpKeys._

import com.typesafe.sbt.osgi.OsgiKeys
import com.typesafe.sbt.osgi.SbtOsgi._

import sbtbuildinfo.BuildInfoPlugin.autoImport._

import sbtunidoc.Plugin._
import sbtunidoc.Plugin.UnidocKeys._

import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport._
import org.scalajs.sbtplugin.cross._

object build extends Build {
  type Sett = Def.Setting[_]

  val isJSProject = SettingKey[Boolean]("isJSProject")

  lazy val publishSignedArtifacts = ReleaseStep(
    action = st => {
      val extracted = st.extract
      val ref = extracted.get(thisProjectRef)
      extracted.runAggregated(publishSigned in Global in ref, st)
    },
    check = st => {
      // getPublishTo fails if no publish repository is set up.
      val ex = st.extract
      val ref = ex.get(thisProjectRef)
      Classpaths.getPublishTo(ex.get(publishTo in Global in ref))
      st
    },
    enableCrossBuild = true
  )

  val scalaCheckVersion = SettingKey[String]("scalaCheckVersion")

  private[this] def gitHash(): String = sys.process.Process("git rev-parse HEAD").lines_!.head

  // no generic signatures for scala 2.10.x, see SI-7932, #571 and #828
  def scalac210Options = Seq("-Yno-generic-signatures")

  private[this] val tagName = Def.setting{
    s"v${if (releaseUseGlobalVersion.value) (version in ThisBuild).value else version.value}"
  }
  private[this] val tagOrHash = Def.setting{
    if(isSnapshot.value) gitHash() else tagName.value
  }

  val scalajsProjectSettings = Seq[Sett](
    isJSProject := true,
    scalacOptions += {
      val a = (baseDirectory in LocalRootProject).value.toURI.toString
      val g = "https://raw.githubusercontent.com/scalaz/scalaz/" + tagOrHash.value
      s"-P:scalajs:mapSourceURI:$a->$g/"
    }
  )

  lazy val notPublish = Seq(
    publishArtifact := false,
    publish := {},
    publishLocal := {},
    publishSigned := {},
    publishLocalSigned := {}
  )

  // avoid move files
  // https://github.com/scala-js/scala-js/blob/v0.6.7/sbt-plugin/src/main/scala/scala/scalajs/sbtplugin/cross/CrossProject.scala#L193-L206
  object ScalazCrossType extends CrossType {
    override def projectDir(crossBase: File, projectType: String) =
      crossBase / projectType
      
    def shared(projectBase: File, conf: String) =
      projectBase.getParentFile / "src" / conf / "scala"

    override def sharedSrcDir(projectBase: File, conf: String) =
      Some(shared(projectBase, conf))
  } 

  lazy val standardSettings: Seq[Sett] = Seq[Sett](
    organization := "org.scalaz",

    scalaVersion := "2.10.6",
    crossScalaVersions := Seq("2.10.6", "2.11.8", "2.12.0-M3"),
    resolvers ++= (if (scalaVersion.value.endsWith("-SNAPSHOT")) List(Opts.resolver.sonatypeSnapshots) else Nil),
    fullResolvers ~= {_.filterNot(_.name == "jcenter")}, // https://github.com/sbt/sbt/issues/2217
    scalaCheckVersion := "1.12.5",
    scalacOptions ++= Seq(
      // contains -language:postfixOps (because 1+ as a parameter to a higher-order function is treated as a postfix op)
      "-deprecation",
      "-encoding", "UTF-8",
      "-feature",
      "-language:implicitConversions", "-language:higherKinds", "-language:existentials", "-language:postfixOps",
      "-unchecked"
    ) ++ (CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2,10)) => scalac210Options
      case Some((2,11)) => Seq(
        "-Ybackend:GenBCode",
        "-Ydelambdafy:method",
        "-target:jvm-1.8"
      )
      case _ => Nil
    }),

    scalacOptions in (Compile, doc) ++= {
      val base = (baseDirectory in LocalRootProject).value.getAbsolutePath
      Seq("-sourcepath", base, "-doc-source-url", "https://github.com/scalaz/scalaz/tree/" + tagOrHash.value + "€{FILE_PATH}.scala")
    },

    // retronym: I was seeing intermittent heap exhaustion in scalacheck based tests, so opting for determinism.
    parallelExecution in Test := false,
    testOptions in Test += {
      val scalacheckOptions = Seq("-maxSize", "5", "-workers", "1", "-maxDiscardRatio", "50") ++ {
        if(isJSProject.value)
          Seq("-minSuccessfulTests", "10")
        else
          Seq("-minSuccessfulTests", "33")
      }
      Tests.Argument(TestFrameworks.ScalaCheck, scalacheckOptions: _*)
    },
    isJSProject := isJSProject.?.value.getOrElse(false),
    genTypeClasses := {
      typeClasses.value.flatMap { tc =>
        val dir = name.value match {
          case ConcurrentName =>
            (scalaSource in Compile).value
          case _ =>
            ScalazCrossType.shared(baseDirectory.value, "main")
        }
        typeclassSource(tc).sources.map(_.createOrUpdate(dir, streams.value.log))
      }
    },
    checkGenTypeClasses <<= genTypeClasses.map{ classes =>
      if(classes.exists(_._1 != FileStatus.NoChange))
        sys.error(classes.groupBy(_._1).filterKeys(_ != FileStatus.NoChange).mapValues(_.map(_._2)).toString)
    },
    typeClasses := Seq(),
    genToSyntax <<= typeClasses map {
      (tcs: Seq[TypeClass]) =>
      val objects = tcs.map(tc => "object %s extends To%sSyntax".format(Util.initLower(tc.name), tc.name)).mkString("\n")
      val all = "object all extends " + tcs.map(tc => "To%sSyntax".format(tc.name)).mkString(" with ")
      objects + "\n\n" + all
    },
    typeClassTree <<= typeClasses map {
      tcs => tcs.map(_.doc).mkString("\n")
    },

    showDoc in Compile <<= (doc in Compile, target in doc in Compile) map { (_, out) =>
      val index = out / "index.html"
      if (index.exists()) Desktop.getDesktop.open(out / "index.html")
    },

    credentialsSetting,
    publishSetting,
    publishArtifact in Test := false,

    // adapted from sbt-release defaults
    // (performs `publish-signed` instead of `publish`)
    releaseProcess := Seq[ReleaseStep](
      checkSnapshotDependencies,
      inquireVersions,
      runTest,
      setReleaseVersion,
      commitReleaseVersion,
      tagRelease,
      publishSignedArtifacts,
      setNextVersion,
      commitNextVersion,
      pushChanges
    ),
    releaseTagName := tagName.value,
    pomIncludeRepository := {
      x => false
    },
    pomExtra := (
      <url>http://scalaz.org</url>
        <licenses>
          <license>
            <name>BSD-style</name>
            <url>http://opensource.org/licenses/BSD-3-Clause</url>
            <distribution>repo</distribution>
          </license>
        </licenses>
        <scm>
          <url>git@github.com:scalaz/scalaz.git</url>
          <connection>scm:git:git@github.com:scalaz/scalaz.git</connection>
        </scm>
        <developers>
          {
          Seq(
            ("runarorama", "Runar Bjarnason"),
            ("pchiusano", "Paul Chiusano"),
            ("tonymorris", "Tony Morris"),
            ("retronym", "Jason Zaugg"),
            ("ekmett", "Edward Kmett"),
            ("alexeyr", "Alexey Romanov"),
            ("copumpkin", "Daniel Peebles"),
            ("rwallace", "Richard Wallace"),
            ("nuttycom", "Kris Nuttycombe"),
            ("larsrh", "Lars Hupel")
          ).map {
            case (id, name) =>
              <developer>
                <id>{id}</id>
                <name>{name}</name>
                <url>http://github.com/{id}</url>
              </developer>
          }
        }
        </developers>
      ),
    // kind-projector plugin
    resolvers += Resolver.sonatypeRepo("releases"),
    addCompilerPlugin("org.spire-math" % "kind-projector" % "0.7.1" cross CrossVersion.binary)
  ) ++ osgiSettings ++ Seq[Sett](
    OsgiKeys.additionalHeaders := Map("-removeheaders" -> "Include-Resource,Private-Package")
  )

  private[this] lazy val jsProjects = Seq[ProjectReference](
    coreJS, effectJS, iterateeJS, scalacheckBindingJS, testsJS
  )

  private[this] lazy val jvmProjects = Seq[ProjectReference](
    coreJVM, effectJVM, iterateeJVM, scalacheckBindingJVM, testsJVM, concurrent, example
  )

  lazy val scalaz = Project(
    id = "scalaz",
    base = file("."),
    settings = standardSettings ++ unidocSettings ++ Seq[Sett](
      artifacts <<= Classpaths.artifactDefs(Seq(packageDoc in Compile)),
      packagedArtifacts <<= Classpaths.packaged(Seq(packageDoc in Compile)),
      unidocProjectFilter in (ScalaUnidoc, unidoc) := {
        jsProjects.foldLeft(inAnyProject)((acc, a) => acc -- inProjects(a))
      }
    ) ++ Defaults.packageTaskSettings(packageDoc in Compile, (unidoc in Compile).map(_.flatMap(Path.allSubpaths))),
    aggregate = jvmProjects ++ jsProjects
  )

  lazy val rootJS = Project(
    "rootJS",
    file("rootJS")
  ).settings(
    standardSettings,
    notPublish
  ).aggregate(jsProjects: _*)

  lazy val rootJVM = Project(
    "rootJVM",
    file("rootJVM")
  ).settings(
    standardSettings,
    notPublish
  ).aggregate(jvmProjects: _*)

  lazy val core = crossProject.crossType(ScalazCrossType)
    .settings(standardSettings: _*)
    .settings(
      name := "scalaz-core",
      sourceGenerators in Compile <+= (sourceManaged in Compile) map {
        dir => Seq(GenerateTupleW(dir), TupleNInstances(dir))
      },
      buildInfoKeys := Seq[BuildInfoKey](version, scalaVersion),
      buildInfoPackage := "scalaz",
      buildInfoObject := "ScalazBuildInfo",
      osgiExport("scalaz"),
      OsgiKeys.importPackage := Seq("javax.swing;resolution:=optional", "*"))
    .enablePlugins(sbtbuildinfo.BuildInfoPlugin)
    .jsSettings(
      scalajsProjectSettings ++ Seq(
        libraryDependencies += "org.scala-js" %%% "scalajs-java-time" % "0.1.0"
      ) : _*
    )
    .jvmSettings(
      libraryDependencies ++= PartialFunction.condOpt(CrossVersion.partialVersion(scalaVersion.value)){
        case Some((2, 11)) => "org.scala-lang.modules" %% "scala-java8-compat" % "0.7.0"
      }.toList,
      typeClasses := TypeClass.core
    )

  lazy val coreJVM = core.jvm
  lazy val coreJS  = core.js

  private final val ConcurrentName = "scalaz-concurrent"

  lazy val concurrent = Project(
    id = "concurrent",
    base = file("concurrent"),
    settings = standardSettings ++ Seq(
      name := ConcurrentName,
      typeClasses := TypeClass.concurrent,
      osgiExport("scalaz.concurrent"),
      OsgiKeys.importPackage := Seq("javax.swing;resolution:=optional", "*")
    ),
    dependencies = Seq(coreJVM, effectJVM)
  )

  lazy val effect = crossProject.crossType(ScalazCrossType)
    .settings(standardSettings: _*)
    .settings(
      name := "scalaz-effect",
      osgiExport("scalaz.effect", "scalaz.std.effect", "scalaz.syntax.effect"))
    .dependsOn(core)
    .jsSettings(scalajsProjectSettings : _*)
    .jvmSettings(
      typeClasses := TypeClass.effect
    )

  lazy val effectJVM = effect.jvm
  lazy val effectJS  = effect.js

  lazy val iteratee = crossProject.crossType(ScalazCrossType)
    .settings(standardSettings: _*)
    .settings(
      name := "scalaz-iteratee",
      osgiExport("scalaz.iteratee"))
    .dependsOn(core, effect)
    .jsSettings(scalajsProjectSettings : _*)

  lazy val iterateeJVM = iteratee.jvm
  lazy val iterateeJS  = iteratee.js

  lazy val example = Project(
    id = "example",
    base = file("example"),
    dependencies = Seq(coreJVM, iterateeJVM, concurrent),
    settings = standardSettings ++ Seq[Sett](
      name := "scalaz-example",
      publishArtifact := false
    )
  )

  lazy val scalacheckBinding =
    CrossProject("scalacheck-binding", file("scalacheck-binding"), ScalazCrossType)
      .settings(standardSettings: _*)
      .settings(
        name := "scalaz-scalacheck-binding",
        libraryDependencies += "org.scalacheck" %%% "scalacheck" % scalaCheckVersion.value,
        osgiExport("scalaz.scalacheck"))
      .dependsOn(core, iteratee)
      .jvmConfigure(_ dependsOn concurrent)
      .jsSettings(scalajsProjectSettings : _*)

  lazy val scalacheckBindingJVM = scalacheckBinding.jvm
  lazy val scalacheckBindingJS  = scalacheckBinding.js

  lazy val tests = crossProject.crossType(ScalazCrossType)
    .settings(standardSettings: _*)
    .settings(
      name := "scalaz-tests",
      publishArtifact := false,
      libraryDependencies += "org.scalacheck" %%% "scalacheck" % scalaCheckVersion.value % "test")
    .dependsOn(core, effect, iteratee, scalacheckBinding)
    .jvmConfigure(_ dependsOn concurrent)
    .jsSettings(scalajsProjectSettings : _*)
    .jsSettings(
      jsEnv := NodeJSEnv().value,
      scalaJSUseRhino in Global := false
    )

  lazy val testsJVM = tests.jvm
  lazy val testsJS  = tests.js

  lazy val publishSetting = publishTo <<= (version).apply{
    v =>
      val nexus = "https://oss.sonatype.org/"
      if (v.trim.endsWith("SNAPSHOT"))
        Some("snapshots" at nexus + "content/repositories/snapshots")
      else
        Some("releases" at nexus + "service/local/staging/deploy/maven2")
  }

  lazy val credentialsSetting = credentials += {
    Seq("build.publish.user", "build.publish.password") map sys.props.get match {
      case Seq(Some(user), Some(pass)) =>
        Credentials("Sonatype Nexus Repository Manager", "oss.sonatype.org", user, pass)
      case _                           =>
        Credentials(Path.userHome / ".ivy2" / ".credentials")
    }
  }

  lazy val genTypeClasses = TaskKey[Seq[(FileStatus, File)]]("gen-type-classes")

  lazy val typeClasses = TaskKey[Seq[TypeClass]]("type-classes")

  lazy val genToSyntax = TaskKey[String]("gen-to-syntax")

  lazy val showDoc = TaskKey[Unit]("show-doc")

  lazy val typeClassTree = TaskKey[String]("type-class-tree", "Generates scaladoc formatted tree of type classes.")

  lazy val checkGenTypeClasses = TaskKey[Unit]("check-gen-type-classes")

  def osgiExport(packs: String*) = OsgiKeys.exportPackage := packs.map(_ + ".*;version=${Bundle-Version}")
}

// vim: expandtab:ts=2:sw=2
