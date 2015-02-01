package ammonite.sh.eval


import acyclic.file
import scala.reflect.internal.util.{OffsetPosition, Position}
import scala.reflect.io
import scala.reflect.io._
import scala.tools.nsc
import scala.tools.nsc.Settings
import scala.tools.nsc.backend.JavaPlatform
import scala.tools.nsc.interactive.{InteractiveAnalyzer, Response}

import scala.tools.nsc.reporters.AbstractReporter
import scala.tools.nsc.typechecker.Analyzer
import scala.tools.nsc.util.ClassPath.JavaContext
import scala.tools.nsc.util.Position
import scala.tools.nsc.util._

object Compiler{
  /**
   * If the Option is None, it means compilation failed
   * Otherwise it's a Traversable of (filename, bytes) tuples
   */
  type Output = Option[Traversable[(String, Array[Byte])]]
  /**
   * Converts a bunch of bytes into Scalac's weird VirtualFile class
   */
  def makeFile(src: Array[Byte], name: String = "Main.scala") = {
    val singleFile = new io.VirtualFile(name)
    val output = singleFile.output
    output.write(src)
    output.close()
    singleFile
  }
}
/**
 * Turns source-strings into the bytes of classfiles, possibly more than one
 * classfile per source-string (e.g. inner classes, or lambdas)
 */
class Compiler(dynamicClasspath: VirtualDirectory) {
  import ammonite.sh.eval.Compiler._

  /**
   * Converts Scalac's weird Future type
   * into a standard scala.concurrent.Future
   */
  def awaitResponse[T](func: Response[T] => Unit): T = {
    val r = new Response[T]
    func(r)
    r.get.fold(
      x => x,
      e => throw e
    )
  }

  var currentLogger: String => Unit = s => ()

  val pressy = {
    val (settings, reporter, vd, jcp) = initGlobalBits(currentLogger)
    new nsc.interactive.Global(settings, reporter) { g =>
      override def classPath = jcp
      override lazy val platform: ThisPlatform = new JavaPlatform{
        val global: g.type = g
        override def classPath = jcp
      }
      override lazy val analyzer = new { val global: g.type = g } with InteractiveAnalyzer {
        override def findMacroClassLoader() = new ClassLoader(this.getClass.getClassLoader){}
      }
    }
  }

  val (vd, reporter, compiler) = {
    val (settings, reporter, vd, jcp) = initGlobalBits(currentLogger)
    val scalac = new nsc.Global(settings, reporter) { g =>
      override def classPath = jcp
      override lazy val platform: ThisPlatform = new JavaPlatform{
        val global: g.type = g
        override def classPath = jcp
      }
      override lazy val analyzer = new { val global: g.type = g } with Analyzer {
        override def findMacroClassLoader() = new ClassLoader(this.getClass.getClassLoader){}
      }
    }
    (vd, reporter, scalac)
  }

  def importsFor(wrapperName: String, allCode: String): Seq[(String, String)] = {
    val filename = wrapperName + ".scala"
    val file = new BatchSourceFile(
      makeFile(allCode.getBytes, name = filename),
      allCode
    )
    awaitResponse[Unit](pressy.askReload(List(file), _))
    def askAt(index: Int) = {
      val position = new OffsetPosition(file, index)
      val scopes = awaitResponse[List[pressy.Member]](
        pressy.askScopeCompletion(position, _)
      )
      scopes.filter(_.accessible)
    }

    def process(members: Seq[pressy.Member]) = pressy.ask { () =>
      members.collect { case x: pressy.ScopeMember =>
        val importTxt =
          x.viaImport
            .toString
            .padTo(30, ' ')
            .replace(".this.", ".")
            .replace("<empty>", wrapperName)

        x.sym.name.toString.trim() -> s"/*$wrapperName*/ import $importTxt"
      }
    }
    val end = process(askAt(allCode.lastIndexOf('}') - 1))
    val start = process(askAt(allCode.indexOf('{') + 1))
    (end.toSet -- start.toSet).toSeq
  }

  def compile(src: Array[Byte], logger: String => Unit = _ => ()): Output = {
    compiler.reporter.reset()
    currentLogger = logger
    val singleFile = makeFile( src)

    val run = new compiler.Run()
    vd.clear()
    run.compileFiles(List(singleFile))
    if (reporter.hasErrors) None
    else Some{
      for{
        x <- vd.iterator.to[collection.immutable.Traversable]
        if x.name.endsWith(".class")
      } yield {
        val output = dynamicClasspath.fileNamed(x.name).output
        output.write(x.toByteArray)
        output.close()
        (x.name.stripSuffix(".class"), x.toByteArray)
      }
    }
  }

  /**
   * Code to initialize random bits and pieces that are needed
   * for the Scala compiler to function, common between the
   * normal and presentation compiler
   */
  def initGlobalBits(logger: => String => Unit)= {
    val vd = new io.VirtualDirectory("(memory)", None)
    lazy val settings = new Settings
    val settingsX = settings
    val jCtx = new JavaContext()
    val jDirs = Classpath.jarDeps.map(x =>
      new DirectoryClassPath(new FileZipArchive(x), jCtx)
    ).toVector ++ Classpath.dirDeps.map(x =>
      new DirectoryClassPath(new PlainDirectory(new Directory(x)), jCtx)
    ) ++ Seq(new DirectoryClassPath(dynamicClasspath, jCtx))
    val jcp = new JavaClassPath(jDirs, jCtx)
    settings.outputDirs.setSingleOutput(vd)

    val reporter = new AbstractReporter {
      def displayPrompt(): Unit = ???

      def display(pos: Position, msg: String, severity: Severity) = {
        severity match{
          case ERROR => logger(
            scala.Console.RED + Position.formatMessage(pos, msg, false) + scala.Console.RESET
          )
          case _ => logger(msg)
        }
      }

      val settings = settingsX
    }
    (settings, reporter, vd, jcp)
  }
}
