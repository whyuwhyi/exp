// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2022 Jiuyang Liu <liu@jiuyang.me>

// Import Mill dependencies
import mill._
import mill.scalalib._
import mill.scalalib.publish._
import coursier.maven.MavenRepository

// Import from each repository
import $file.dependencies.`fpuv2`.build
import $file.common

// Define Scala and Chisel versions
object v {
  val scalaVersions = Map(
    "6.4.0" -> "2.13.12",
    // "3.6.0" -> "2.13.10",
    // "3.5.0" -> "2.13.7",
  )
  val scalaReflect = Map(
    "6.4.0" -> ivy"org.scala-lang:scala-reflect:2.13.12",
    // "3.6.0" -> ivy"org.scala-lang:scala-reflect:2.13.10",
    // "3.5.0" -> ivy"org.scala-lang:scala-reflect:2.13.7",
  )
  val chiselCrossVersions = Map(
    "6.4.0" -> (ivy"org.chipsalliance::chisel:6.4.0", ivy"org.chipsalliance:::chisel-plugin:6.4.0", ivy"edu.berkeley.cs::chiseltest:6.0.0"),
    // "3.6.0" -> (ivy"edu.berkeley.cs::chisel3:3.6.0", ivy"edu.berkeley.cs:::chisel3-plugin:3.6.0", ivy"edu.berkeley.cs::chiseltest:0.6.2"),
    // "3.5.0" -> (ivy"edu.berkeley.cs::chisel3:3.5.0", ivy"edu.berkeley.cs:::chisel3-plugin:3.5.0", ivy"edu.berkeley.cs::chiseltest:0.5.0"),
  )
}

// Define fpuv2 module
object fpuv2 extends Cross[FPUv2](v.chiselCrossVersions.keys.toSeq)
trait FPUv2 extends SbtModule
  with millbuild.dependencies.`fpuv2`.build.FPUv2Module {
  def chiselVersion: String = crossValue
  def scalaVersion = v.scalaVersions(chiselVersion)
  def chiselIvy = Some(v.chiselCrossVersions(chiselVersion)._1)
  def chiselPluginIvy = Some(v.chiselCrossVersions(chiselVersion)._2)
  override def millSourcePath = os.pwd / "dependencies" / "fpuv2"
  def fudianModule = fudian(crossValue)
  override def scalacPluginIvyDeps = super.scalacPluginIvyDeps() ++ Agg(chiselPluginIvy.get)

  object fudian extends Cross[FuDian](crossValue)
  trait FuDian extends SbtModule
    with millbuild.common.HasChisel with Cross.Module[String] {
    def chiselVersion: String = crossValue
    def scalaVersion = v.scalaVersions(chiselVersion)
    def chiselIvy = Some(v.chiselCrossVersions(chiselVersion)._1)
    def chiselPluginIvy = Some(v.chiselCrossVersions(chiselVersion)._2)
    override def millSourcePath = os.pwd / "dependencies" / "fpuv2" / "fudian"
  }
}

object EXPFP32 extends Cross[EXPFP32](v.chiselCrossVersions.keys.toSeq)
trait EXPFP32
  extends millbuild.common.VentusModule
    with Cross.Module[String] {
  def chiselVersion: String = crossValue
  def scalaVersion = v.scalaVersions(chiselVersion)
  def chiselIvy = Some(v.chiselCrossVersions(chiselVersion)._1)
  def chiselPluginIvy = Some(v.chiselCrossVersions(chiselVersion)._2)

  override def millSourcePath = os.pwd

  def hardfloatModule = hardfloat(crossValue)
  def fpuv2Module = fpuv2(crossValue)
  def ivyDeps = super.ivyDeps() ++ Agg(
      ivy"io.circe::circe-core:0.14.6",
      ivy"io.circe::circe-generic:0.14.6",
      ivy"io.circe::circe-parser:0.14.6",
    )

  override def forkArgs = Seq("-Xmx32G", "-Xss192m")
  override def scalacOptions = super.scalacOptions() ++ Seq(
    "-language:reflectiveCalls",
    "-Ymacro-annotations",
    "-deprecation",
    "-feature",
    "-Xcheckinit"
  )

  override def scalacPluginIvyDeps = super.scalacPluginIvyDeps() ++ Agg(chiselPluginIvy.get)
}

