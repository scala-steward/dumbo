// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo

import java.io.File
import java.nio.file.Paths

import scala.jdk.CollectionConverters.*
import scala.quoted.*

import fs2.io.file.Path

opaque type ResourceFilePath = String
object ResourceFilePath:
  inline def fromResourcesDir(name: String): List[ResourceFilePath] =
    ${ listResourcesImpl('name) }

  private def listResourcesImpl(x: Expr[String])(using Quotes): Expr[List[ResourceFilePath]] =
    import quotes.reflect.report
    val location = x.valueOrAbort

    getClass().getClassLoader().getResources(location).asScala.toList match
      case head :: Nil =>
        if head.toString.startsWith("jar:") then
          val srcUriStr   = head.toURI().toString()
          val jarFilePath = srcUriStr.slice(srcUriStr.lastIndexOf(":") + 1, srcUriStr.lastIndexOf("!"))

          val resources = scala.util.Using.resource(java.util.zip.ZipFile(jarFilePath)) { fs =>
            fs
              .entries()
              .asScala
              .toList
              .filter(e => e.getName().startsWith(location) && !e.isDirectory())
              .map(entry => s"/${entry.getName()}")
          }

          Expr(resources)
        else
          @scala.annotation.tailrec
          def listRec(dirs: List[File], files: List[File]): List[File] =
            dirs match
              case x :: xs =>
                val (d, f) = x.listFiles().toList.partition(_.isDirectory())
                listRec(d ::: xs, f ::: files)
              case Nil => files

          val base = Paths.get(head.toURI())
          val resources = listRec(List(File(base.toString())), Nil).map(f =>
            s"/$location/${base.relativize(Paths.get(f.getAbsolutePath()))}"
          )
          Expr(resources)
      case Nil => report.errorAndAbort(s"resource ${location} was not found")
      case multiple =>
        report.errorAndAbort(s"found multiple resource locations for ${location} in:\n${multiple.mkString("\n")}")

  private def fromResource(x: Expr[String])(using Quotes): Expr[ResourceFilePath] =
    import quotes.reflect.report
    val location = x.valueOrAbort
    if getClass().getResourceAsStream(location) != null then x
    else report.errorAndAbort(s"resource ${location} was not found")

  inline def fromResource(name: String): ResourceFilePath = ${ fromResource('name) }
  def apply(name: String): ResourceFilePath               = name

  extension (s: ResourceFilePath)
    inline def value: String                       = s
    inline def append(p: String): ResourceFilePath = s + p
    inline def fileName: String                    = Path(s).fileName.toString
