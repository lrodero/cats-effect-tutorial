name := "cats-effect-tutorial"

version := "3.3.0-M5"

scalaVersion := "2.13.3"

libraryDependencies += "org.typelevel" %% "cats-effect" % "3.0.0-M5" withSources() withJavadoc()

scalacOptions ++= Seq(
  "-feature",
  "-deprecation",
  "-unchecked",
  "-language:postfixOps",
  "-language:higherKinds")
