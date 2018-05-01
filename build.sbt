import sbt._
import Keys._
import play.sbt.PlayScala

lazy val appVersion = "0.3.1-SNAPSHOT"

lazy val catsVersion  = "1.0.1"
lazy val circeVersion = "0.9.3"

organization := "com.passivsystems"
name         := "play-workflow"
version      := appVersion

scalaVersion       := "2.11.8"
crossScalaVersions := Seq("2.11.8", "2.12.1")

libraryDependencies ++= Seq(
  "org.typelevel"  %% "cats-core"     % catsVersion,
  "org.typelevel"  %% "cats-free"     % catsVersion,
  compilerPlugin("org.spire-math" %% "kind-projector" % "0.9.3"),
  "io.circe"       %% "circe-parser"  % circeVersion,
  "io.circe"       %% "circe-generic" % circeVersion,
  // Test
  "org.scalatest"  %% "scalatest"     % "3.0.0"     % Test,
  "org.scalacheck" %% "scalacheck"    % "1.13.4"    % Test,
  "org.typelevel"  %% "cats-laws"     % catsVersion % Test
)

scalaSource in Compile := baseDirectory.value / "src/main/scala"
scalaSource in Test    := baseDirectory.value / "src/test/scala"

scalacOptions ++= Seq(
  //"-Xlog-implicits",
  "-Xlint",
  "-deprecation",
  "-feature",
  "-language:higherKinds",
  "-Xfuture",
  "-Ywarn-unused-import",
  "-Ywarn-value-discard",
  "-target:jvm-1.7" // only for Scala 2.11 (will be ignored for 2.12)
)

enablePlugins(PlayScala)