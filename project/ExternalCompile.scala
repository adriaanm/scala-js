package build

import sbt._
import Keys._

import org.scalajs.sbtplugin.ScalaJSPlugin

object ExternalCompile {

  private val isWindows =
    System.getProperty("os.name").toLowerCase().indexOf("win") >= 0

  val scalaJSExternalCompileConfigSettings: Seq[Setting[_]] = inTask(compile)(
      Defaults.runnerTask
  ) ++ Seq(
      fork in compile := true,
      trapExit in compile := true,
      javaOptions in compile += "-Xmx512M",

      javaOptions in compile ++= {
        val scalaExtDirs = System.getProperty("scala.ext.dirs")
        if (scalaExtDirs != null && (fork in compile).value)
          Seq("-Dscala.ext.dirs=" + scalaExtDirs)
        else
          Nil
      },

      compile := {
        val inputs = (compileInputs in compile).value
        import inputs.config._

        val s = streams.value
        val logger = s.log
        val cacheDir = s.cacheDirectory

        // Discover classpaths

        def cpToString(cp: Seq[File]) =
          cp.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator)

        val compilerCp = inputs.compilers.scalac.scalaInstance.allJars
        val cpStr = cpToString(classpath)

        // List all my dependencies (recompile if any of these changes)

        val allMyDependencies = classpath filterNot (_ == classesDirectory) flatMap { cpFile =>
          if (cpFile.isDirectory) (cpFile ** "*.class").get
          else Seq(cpFile)
        }

        // Compile

        val cachedCompile = FileFunction.cached(cacheDir / "compile",
            FilesInfo.lastModified, FilesInfo.exists) { dependencies =>

          logger.info(
              "Compiling %d Scala sources to %s..." format (
              sources.size, classesDirectory))

          if (classesDirectory.exists)
            IO.delete(classesDirectory)
          IO.createDirectory(classesDirectory)

          // val sourcesArgs = sources.map(_.getAbsolutePath()).toList

          val sourcesArgs = {
            val tmp = sources.map(_.getAbsolutePath()).toList.sorted
            println(sources)
            if (tmp.head.contains("javalanglib")) {
              val Array(pre, _) = tmp.head.split("javalanglib")
              List("javalanglib/src/main/scala/java/lang/ThreadLocal.scala", "javalanglib/src/main/scala/java/lang/Thread.scala", "javalanglib/src/main/scala/java/lang/Appendable.scala", "javalanglib/src/main/scala/java/lang/Runnable.scala", "javalanglib/src/main/scala/java/lang/ObjectClone.scala", "javalanglib/src/main/scala/java/lang/Readable.scala", "javalanglib/src/main/scala/java/lang/Class.scala", "javalanglib/src/main/scala/java/lang/Comparable.scala", "javalanglib/src/main/scala/java/lang/Void.scala", "javalanglib/src/main/scala/java/lang/Runtime.scala", "javalanglib/src/main/scala/java/lang/System.scala", "javalanglib/src/main/scala/java/lang/StackTrace.scala", "javalanglib/src/main/scala/java/lang/CharSequence.scala", "javalanglib/src/main/scala/java/lang/ClassLoader.scala", "javalanglib/src/main/scala/java/lang/Throwables.scala", "javalanglib/src/main/scala/java/lang/Iterable.scala", "javalanglib/src/main/scala/java/lang/StringBuilder.scala", "javalanglib/src/main/scala/java/lang/Number.scala", "javalanglib/src/main/scala/java/lang/Enum.scala", "javalanglib/src/main/scala/java/lang/Long.scala", "javalanglib/src/main/scala/java/lang/FloatingPointBits.scala", "javalanglib/src/main/scala/java/lang/Cloneable.scala", "javalanglib/src/main/scala/java/lang/Character.scala", "javalanglib/src/main/scala/java/lang/Short.scala", "javalanglib/src/main/scala/java/lang/Byte.scala", "javalanglib/src/main/scala/java/lang/StackTraceElement.scala", "javalanglib/src/main/scala/java/lang/_String.scala", "javalanglib/src/main/scala/java/lang/Float.scala", "javalanglib/src/main/scala/java/lang/StringBuffer.scala", "javalanglib/src/main/scala/java/lang/AutoCloseable.scala", "javalanglib/src/main/scala/java/lang/Integer.scala", "javalanglib/src/main/scala/java/lang/Math.scala", "javalanglib/src/main/scala/java/lang/Boolean.scala", "javalanglib/src/main/scala/java/lang/Double.scala", "javalanglib/src/main/scala/java/lang/InheritableThreadLocal.scala", "javalanglib/src/main/scala/java/lang/reflect/Array.scala", "javalanglib/src/main/scala/java/lang/annotation/Annotation.scala", "javalanglib/src/main/scala/java/lang/ref/PhantomReference.scala", "javalanglib/src/main/scala/java/lang/ref/WeakReference.scala", "javalanglib/src/main/scala/java/lang/ref/SoftReference.scala", "javalanglib/src/main/scala/java/lang/ref/Reference.scala", "javalanglib/src/main/scala/java/lang/ref/ReferenceQueue.scala"
              ).map(file => pre + file)
            }
            else tmp
          }
          
          /* run.run() below in doCompileJS() will emit a call to its
           * logger.info("Running scala.tools.nsc.scalajs.Main [...]")
           * which we do not want to see. We use this patched logger to
           * filter out that particular message.
           */
          val patchedLogger = new Logger {
            def log(level: Level.Value, message: => String) = {
              val msg = message
              if (level != Level.Info ||
                  !msg.startsWith("Running scala.tools.nsc.Main"))
                logger.log(level, msg)
            }
            def success(message: => String) = logger.success(message)
            def trace(t: => Throwable) = logger.trace(t)
          }

          def doCompile(sourcesArgs: List[String]): Unit = {
            val run = (runner in compile).value
            val optErrorMsg = run.run("scala.tools.nsc.Main", compilerCp,
                "-cp" :: cpStr ::
                "-d" :: classesDirectory.getAbsolutePath() ::
                options ++:
                sourcesArgs,
                patchedLogger)
            optErrorMsg.foreach(errorMsg => throw new Exception(errorMsg))
          }

          /* Crude way of overcoming the Windows limitation on command line
           * length.
           */
          if ((fork in compile).value && isWindows &&
              (sourcesArgs.map(_.length).sum > 1536)) {
            IO.withTemporaryFile("sourcesargs", ".txt") { sourceListFile =>
              IO.writeLines(sourceListFile, sourcesArgs)
              doCompile(List("@"+sourceListFile.getAbsolutePath()))
            }
          } else {
            doCompile(sourcesArgs)
          }

          // Output is all files in classesDirectory
          (classesDirectory ** AllPassFilter).get.toSet
        }

        cachedCompile((sources ++ allMyDependencies).toSet)

        // We do not have dependency analysis when compiling externally
        sbt.inc.Analysis.Empty
      }
  )

  val scalaJSExternalCompileSettings = (
      inConfig(Compile)(scalaJSExternalCompileConfigSettings) ++
      inConfig(Test)(scalaJSExternalCompileConfigSettings)
  )

}
