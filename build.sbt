name := "cats-effect-tutorial"

version := "3.0.0-RC2"

scalaVersion := "2.13.4"

libraryDependencies += "org.typelevel" %% "cats-effect" % "3.0.0-RC2" withSources() withJavadoc()

scalacOptions ++= Seq(
  "-feature",
  "-deprecation",
  "-unchecked",
  "-language:postfixOps")
