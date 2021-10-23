name := "cats-effect-tutorial"

version := "3.2.9"

scalaVersion := "2.13.6"

libraryDependencies += "org.typelevel" %% "cats-effect" % "3.2.9" withSources() withJavadoc()

scalacOptions ++= Seq(
  "-feature",
  "-deprecation",
  "-unchecked",
  "-language:postfixOps")
