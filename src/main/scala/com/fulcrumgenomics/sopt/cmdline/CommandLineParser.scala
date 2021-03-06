/*
 * The MIT License
 *
 * Copyright (c) 2015-2016 Fulcrum Genomics LLC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.fulcrumgenomics.sopt.cmdline

import com.fulcrumgenomics.commons.CommonsDef._
import com.fulcrumgenomics.commons.reflect.ReflectionUtil
import com.fulcrumgenomics.sopt.Sopt.{CommandSuccess, Failure, Result, SubcommandSuccess}
import com.fulcrumgenomics.sopt.{Sopt, clp}
import com.fulcrumgenomics.sopt.util.ParsingUtil._
import com.fulcrumgenomics.sopt.util._

import scala.collection.mutable.ListBuffer
import scala.collection.{Set, mutable}
import scala.reflect.ClassTag
import scala.reflect.runtime.universe._

/** Various constants and methods for formatting and printing usages and error messages on the command line */
trait CommandLineParserStrings {
  /** Lengths for names and descriptions on the command line */
  val SubCommandGroupNameColumnLength: Int = 38
  val SubCommandGroupDescriptionColumnLength: Int = Sopt.TerminalWidth - SubCommandGroupNameColumnLength
  val SubCommandNameColumnLength: Int = SubCommandGroupNameColumnLength - 3

  /** The maximum line lengths for tool descriptions */
  val SubCommandDescriptionLineLength: Int = SubCommandGroupDescriptionColumnLength - 1

  /** Error messages */
  val AvailableSubCommands: String = "Available Sub-Commands:"
  val MissingSubCommand: String = "No sub-command given."

  /** Section headers */
  val UsagePrefix: String = "USAGE:"
  val ArgumentsSuffix: String = "Arguments:"
  private[cmdline] val RequiredArguments: String = "Required Arguments:"
  private[cmdline] val OptionalArguments: String = "Optional Arguments:"

  /** Section separator */
  val SeparatorLine: String = KWHT("-" * Sopt.TerminalWidth + "\n")

  /** Similarity floor for when searching for similar sub-command names. **/
  val HelpSimilarityFloor: Int = 7
  val MinimumSubstringLength: Int = 5

  /** The command line name. */
  def commandLineName: String

  /** Formats the short description of a tool when printing out the list of sub-commands in the usage.
    *
    * (1) find the first period (".") and keep only everything before it
    * (2) shorten it to the maximum line length adding "..." to the end.
    */
  def formatShortDescription(description: String): String = {
    val desc = description.stripMargin.dropWhile(_ == '\n')
    desc.indexOf('.') match {
      case -1 =>
        if (desc.length > SubCommandDescriptionLineLength-3) desc.substring(0, SubCommandDescriptionLineLength-3) + "..."
        else desc
      case idx => formatShortDescription(desc.substring(0, idx)) + "."
    }
  }

  /** Wraps an error string in terminal escape codes for display. */
  private[cmdline] def wrapError(s: String) : String = KERROR(s)

  /**
    * A typical command line program will call this to get the beginning of the usage message,
    * and then append a description of the program.
    */
  def standardSubCommandUsagePreamble(clazz: Option[Class[_]] = None): String = {
    standardCommandAndSubCommandUsagePreamble(commandClazz=None, subCommandClazz=clazz)
  }

  /** The name of "command" on the command line */
  def genericClpNameOnCommandLine: String = "command"

  /**
    * A typical command line program will call this to get the beginning of the usage message,
    * and then append a description of the program.
    */
  def standardCommandAndSubCommandUsagePreamble(commandClazz: Option[Class[_]] = None, subCommandClazz: Option[Class[_]] = None): String = {
    (commandClazz, subCommandClazz) match {
      case (Some(commandCz), Some(subCommandCz)) =>
        val mainName = commandLineName // commandCz.getSimpleName
        val clpName = subCommandCz.getSimpleName
        s"${KRED(UsagePrefix)} ${KBLDRED(mainName)} ${KRED(s"[$mainName arguments] [$genericClpNameOnCommandLine name] [$genericClpNameOnCommandLine arguments]")}" // FIXME: use clpName
      case (Some(commandCz), None) =>
        val mainName = commandLineName //mainCz.getSimpleName
        s"${KRED(UsagePrefix)} ${KBLDRED(mainName)} ${KRED(s"[$mainName arguments] [$genericClpNameOnCommandLine name] [$genericClpNameOnCommandLine arguments]")}"
      case (None, Some(subCommandCz)) =>
        val clpName = subCommandCz.getSimpleName
        s"${KRED(UsagePrefix)} ${KBLDRED(clpName)} ${KRED(s"[arguments]")}"
      case (None, None) =>
        s"${KRED(UsagePrefix)} ${KBLDRED(commandLineName)} ${KRED(s"[$genericClpNameOnCommandLine] [arguments]")}"
    }
  }
}

/** Class for parsing the command line when we also have sub-commands.
  *
  * We have two possible entry points:
  * (1) [[parseSubCommand()]] for when we have set of sub-commands, each having their own set of arguments, and we
  *     use the first argument to get the class's simple name (`getSimpleName`), and pass the rest of the arguments
  *     to a properly instantiated [[CommandLineProgramParser]] to parse the arguments and build an instance of that
  *     class.
  * (2) [[parseCommandAndSubCommand]] for the same case as (1), but when the main program itself also has arguments, which
  *     come prior to the name of the command line program.
  *
  * To route the arguments to build the appropriate program, the parser searches for a command line program that is a
  * sub-class of [[SubCommand]], and creates a [[CommandLineProgramParser]] to parse the args and return an instance of the
  * [[SubCommand]].
  *
  * Constructor arguments for the sub-command classes or command class can be annotated with [[com.fulcrumgenomics.sopt.arg]] while
  * constructors themselves can be annotated with [[clp]].  The latter may be omitted, but is extremely
  * useful for grouping related sub-commands and having a common description.
  *
  * @param commandLineName the name of the base command line.
  * @param classTag the [[ClassTag]] of [[SubCommand]] , usually inferred.
  * @param typeTag the [[TypeTag]] of [[SubCommand]] , usually inferred.
  * @tparam SubCommand the base class for all sub-commands to display and parse on the command line.
  */
class CommandLineParser[SubCommand](val commandLineName: String)
                                   (implicit classTag: ClassTag[SubCommand], typeTag: TypeTag[SubCommand])
  extends CommandLineParserStrings {

  private type SubCommandClass = Class[_ <: SubCommand]
  private type SubCommandGroupClass = Class[_ <: ClpGroup]

  private var _commandLine: Option[String] = None
  private val usagePrinter = new StringBuilder()

  private def print(s: String) = usagePrinter.append(s).append("\n")

  /** The command line with all arguments and values stored after the [[parseSubCommand()]] or
    * [[parseCommandAndSubCommand()]] was successful, otherwise [[None]]. */
  def commandLine: Option[String] = _commandLine

  /** The error message for an unknown sub-command. */
  private[cmdline] def unknownSubCommandErrorMessage(command: String, classes: Iterable[SubCommandClass] = Set.empty): String = {
    s"'$command' is not a valid sub-command. See $commandLineName --help for more information." + printUnknown(command, classes.map(_.getSimpleName))
  }

  /**
    * Finds the class of a sub-command using the first element in the args array.
    *
    * @param args the arguments, with the first argument used as the sub-command name.
    * @param classes the set of available sub-command classes.
    * @return the class of the sub-commands or an error string if either no arguments were given or no
    *         sub-command class matched the given argument.
    */
  private def parseSubCommandName(args: Seq[String], classes: Iterable[SubCommandClass]): Either[SubCommandClass,String] = {
    if (args.length < 1) {
      Right(MissingSubCommand)
    }
    else {
      val clazzOption: Option[SubCommandClass] = classes.find(clazz => 0 == args.head.compareTo(clazz.getSimpleName))
      clazzOption match {
        case Some(clazz) => Left(clazz)
        case None        => Right(unknownSubCommandErrorMessage(args.head, classes))
      }
    }
  }

  /**
    * Prints the top-level usage of the command line, with a standard pre-amble, version, and list of sub-commands available.
    *
    * @param classes the classes corresponding to the sub-commands.
    * @param commandLineName the name of this sub-command.
    */
  private[cmdline] def subCommandListUsage(classes: Iterable[SubCommandClass], commandLineName: String, withPreamble: Boolean): String = {
    val builder = new StringBuilder

    if (withPreamble) {
      builder.append(s"${standardSubCommandUsagePreamble()}\n")
      builder.append(CommandLineProgramParserStrings.version(getClass, color=true) + "\n")
      builder.append(CommandLineProgramParserStrings.lineBreak(color=true) + "\n")
    }

    builder.append(KBLDRED(s"$AvailableSubCommands\n"))
    val subCommandsGroupClassToClpGroupInstance: mutable.Map[SubCommandGroupClass, ClpGroup] = new mutable.HashMap[SubCommandGroupClass, ClpGroup]
    val subCommandsByGroup: java.util.Map[ClpGroup, ListBuffer[SubCommandClass]] = new java.util.TreeMap[ClpGroup, ListBuffer[SubCommandClass]]()
    val subCommandsToClpAnnotation: mutable.Map[SubCommandClass, clp] = new mutable.HashMap[SubCommandClass, clp]

    classes.foreach { clazz =>
      findClpAnnotation(clazz) match {
        case None => throw new BadAnnotationException(s"The class '${clazz.getSimpleName}' is missing the required @clp annotation.")
        case Some(clp) =>
          subCommandsToClpAnnotation.put(clazz, clp)
          val clpGroup: ClpGroup = subCommandsGroupClassToClpGroupInstance.get(clp.group) match {
            case Some(group) => group
            case None =>
              val group: ClpGroup = clp.group.newInstance
              subCommandsGroupClassToClpGroupInstance.put(clp.group, group)
              group
          }
          Option(subCommandsByGroup.get(clpGroup)) match {
            case Some(clps) =>
              clps += clazz
            case None =>
              val clps = ListBuffer[SubCommandClass](clazz)
              subCommandsByGroup.put(clpGroup, clps)
          }
      }
    }

    subCommandsByGroup.entrySet.foreach{entry =>
      val clpGroup: ClpGroup = entry.getKey
      builder.append(SeparatorLine)
      builder.append(KRED(String.format(s"%-${SubCommandGroupNameColumnLength}s%-${SubCommandGroupDescriptionColumnLength}s\n",
        clpGroup.name + ":", clpGroup.description)))
      val entries: List[SubCommandClass] = entry.getValue.toList
      entries
        .sortWith((lhs, rhs) => lhs.getSimpleName.compareTo(rhs.getSimpleName) < 0)
        .foreach{clazz =>
          val clp: clp = subCommandsToClpAnnotation.get(clazz).get
          builder.append(KGRN(String.format(s"    %-${SubCommandNameColumnLength}s%s\n",
            clazz.getSimpleName, KCYN(formatShortDescription(clp.description)))))
        }
    }

    builder.append(SeparatorLine)
    builder.toString()
  }

  /** The main method for parsing command line arguments.  Typically, the arguments are the format
    * `[clp Name] [clp arguments]`.  This in contrast to [[parseCommandAndSubCommand]], which includes arguments to the main class in
    * the `[main arguments]` section.
    *
    *   (1) Finds all classes that extend [[SubCommand]] to include them as valid command line programs on the command line.
    *   (2) When the first arg is one of the command line programs names (usually `getSimpleName`), it creates a
    *       [[CommandLineProgramParser]] (SubCommand parser) for the given command line program, and allows the SubCommand parser to
    *       parse the rest of the args.
    *   (3) Returns `None` in the case of any parsing error, or an instance of the command line program constructed
    *       according to the parsed arguments.
    *
    * @param args        the command line arguments to parse.
    * @param subcommands the set of possible sub-command types/classes
    * @param withVersion true to print the version in the usage messages, false otherwise.
    * @param withSpecialArgs if true include help and version arguments
    * @param extraUsage  an optional string that will be printed prior to any usage message.
    * @return            a [[clp]] instance initialized with the command line arguments, or [[None]] if
    *                    unsuccessful.
    */
  def parseSubCommand(args: Seq[String],
                      subcommands: Iterable[Class[_ <: SubCommand]],
                      withVersion: Boolean = true,
                      withSpecialArgs: Boolean = true, 
                      extraUsage: Option[String] = None): Result[_ <: SubCommand,Nothing] = {

    /** A brief developer note about the precedence of printing error messages and usages.
      *
      * See [[parseCommandAndSubCommand]] for 1-3, for when we have options to the main tool.
      *
      * 4. Complain if (a) no clp name is given, or (b) it is not recognized.
      * 5. Complain if clp options are mis-specified.
      * 6. Print the clp help message if --help is used after a clp name.
      * 7. Print the version if --version is used after a clp name.
      * 8. ... execute the clp ...
      * */

    // Try parsing the task name
    parseSubCommandName(args = args, classes=subcommands) match {
      case Right(error) => // Case 4 (b)
        val withPreamble = extraUsage match {
          case Some(str) => print(str); false
          case None => true
        }
        print(subCommandListUsage(subcommands, commandLineName, withPreamble=withPreamble))
        print(wrapError(error))
        Failure(() => usagePrinter.toString())
      case Left(clazz) =>
        def printExtraUsage(clazz: Option[Class[_]]): Unit = {
          extraUsage match {
            case Some(str) => print(str)
            case None =>
          }
        }
        /////////////////////////////////////////////////////////////////////////
        // Parse the arguments for the Command Line Program class
        /////////////////////////////////////////////////////////////////////////
        // FIXME: could not get this to work as an anonymous subclass, so I just extended it.
        class ClpParser[SubCommand](targetClass: Class[SubCommand]) extends CommandLineProgramParser[SubCommand](targetClass, withSpecialArgs) {
          override protected def standardUsagePreamble: String = {
            extraUsage match {
              case Some(_) => s"${KBLDRED(targetName)}"
              case None => standardSubCommandUsagePreamble(Some(clazz))
            }
          }
        }
        val clpParser = new ClpParser(clazz)
        clpParser.parseAndBuild(args.drop(1)) match {
          case ParseFailure(ex, _) => // Case 5
            printExtraUsage(clazz=Some(clazz))
            print(clpParser.usage(withVersion=withVersion))
            print(wrapError(ex.getMessage))
            Failure(() => usagePrinter.toString())
          case ParseHelp() => // Case 6
            printExtraUsage(clazz=Some(clazz))
            print(clpParser.usage(withVersion=withVersion))
            Failure(() => usagePrinter.toString())
          case ParseVersion()  => // Case 7
            print(clpParser.version)
            Failure(() => usagePrinter.toString())
          case ParseSuccess() => // Case 8
            val clp: SubCommand = clpParser.instance.get
            try {
              _commandLine = Some(_commandLine.map(_ + " ").getOrElse("") + clpParser.commandLine())
              CommandSuccess(clp)
            }
            catch {
              case ex: ValidationException =>
                printExtraUsage(clazz=Some(clazz))
                print(clpParser.usage(withVersion=withVersion))
                ex.messages.foreach(msg => print(wrapError(msg)))
                Failure(() => usagePrinter.toString())
            }
        }
    }
  }

  /** Method for parsing command arguments as well as a specific sub-command arguments.
    * Typically, the arguments are the format `[command arguments] [sub-command] [sub-command arguments]`.  This in
    * contrast to [[parseSubCommand]], which omits the `[command arguments]` section.
    *
    *   (1) Searches for `--` or a recognized [[SubCommand]] in the list of arguments to partition the main and clp arguments. The
    *       [[SubCommand]]s are found by searching for all classes that extend [[SubCommand]].
    *   (2) Parses the main arguments and creates an instance of main, and returns None if any error occurred.
    *   (3) Executes the `afterMainBuilding` of code after the main instance has been created.
    *   (3) Parses the clp arguments and creates an instance of clp, and returns None if any error occurred.
    *   (3) Executes the `afterClpBuilding` of code after the clp instance has been created.
    *
    * It creates [[CommandLineProgramParser]]s to parse the main class and clp arguments respectively.
    *
    * @param args        the command line arguments to parse.
    * @param subcommands the set of available sub-commands
    *
    * @return the [[Command]] and [[SubCommand]] instances initialized with the command line arguments, or [[None]] if unsuccessful.
    */
  def parseCommandAndSubCommand[Command](args: Seq[String], subcommands: Iterable[Class[_ <: SubCommand]])
                                        (implicit tt: TypeTag[Command]): Result[_ <: Command,_ <: SubCommand] = {
    val mainClazz: Class[Command] = ReflectionUtil.typeTagToClass[Command]
    val thisParser = this

    // Parse the args for the main class
    val mainClassParser = new CommandLineProgramParser(mainClazz, includeSpecialArgs = true) {
      override protected def standardUsagePreamble: String = {
        standardCommandAndSubCommandUsagePreamble(Some(mainClazz), None)
      }
      override protected def targetName: String = thisParser.commandLineName
      override def commandLineName: String = thisParser.commandLineName
      override def genericClpNameOnCommandLine: String = thisParser.genericClpNameOnCommandLine
    }

    val (mainClassArgs, clpArgs) = splitArgs(args, subcommands)

    /** A brief developer note about the precedence of printing error messages and usages.
      *
      * 1. Complain if (a) command options are mis-specified, or (b) the clp name was not recognized (must be the last
      *    argument in the `mainClassArgs` and have no leading dash).
      * 2. Print the commnd-only help message if --help is used before a clp name.
      * 3. Print the version if --version is used before a clp name.
      * 4. Complain if (a) no clp name is given, or (b) it is not recognized.
      * 5. Complain if clp options are mis-specified.
      * 6. Print the command-and-sub-command help message if --help is used after a clp name.
      * 7. Print the version if --version is used after a clp name.
      * 8. ... execute the sub-command ...
      * */

    /////////////////////////////////////////////////////////////////////////
    // Try parsing and building CommandClass and handle the outcomes
    /////////////////////////////////////////////////////////////////////////
    mainClassParser.parseAndBuild(args=mainClassArgs) match {
      case ParseFailure(ex, _) => // Case (1)
        print(mainClassParser.usage())
        print(wrapError(ex.getMessage))
        Failure(() => usagePrinter.toString())
      case ParseHelp() => // Case (2)
        print(mainClassParser.usage())
        print(subCommandListUsage(subcommands, commandLineName, withPreamble=true))
        Failure(() => usagePrinter.toString())
      case ParseVersion() => // Case (3)
        print(mainClassParser.version)
        Failure(() => usagePrinter.toString())
      case ParseSuccess() => // Case (4-8)
        /////////////////////////////////////////////////////////////////////////
        // Get setup, and attempt to ID and load the clp class
        /////////////////////////////////////////////////////////////////////////
        val mainInstance = mainClassParser.instance.get
        this._commandLine = Some(mainClassParser.commandLine())

        this.parseSubCommand(args=clpArgs, subcommands=subcommands, extraUsage = Some(mainClassParser.usage()), withVersion=false) match {
          case f: Failure          => f
          case CommandSuccess(sub) => SubcommandSuccess(mainInstance, sub)
          case other               => unreachable("Why did parseSubCommand return " + other)
        }
    }
  }

  /** Splits the given args into two Arrays, first splitting based on a "--", and if not found,
    * searching for a program name.  The "--" arg will not be returned.
    */
  private[cmdline] def splitArgs(args: Seq[String], subcommands: Iterable[SubCommandClass]) : (Seq[String], Seq[String]) = {
    if (args.isEmpty) return (args, Seq.empty)

    // first check for "--"
    args.indexOf("--") match {
      case -1 =>
        // check for an exact match
        subcommands.view.map { p => args.indexOf(p.getSimpleName) }.filter(_ != -1).reduceOption(_ min _) match {
          case Some(n) => args.splitAt(n)
          case None => // args must be non-empty
            // try finding the most similar arg and pipeline name
            val distances = args.toList.map { arg =>
              if (arg.startsWith("-")) Integer.MAX_VALUE // ignore obvious options
              else findSmallestSimilarityDistance(arg, subcommands.map(_.getSimpleName))
            }
            // if we found one that was similar, then split the args at that point
            distances.zipWithIndex.min match {
              case (distance, idx) if distance < Integer.MAX_VALUE => args.splitAt(idx)
              case (distance, idx) => (args, Seq.empty)
            }
        }
      case idx =>
        (args.take(idx), args.drop(idx+1))
    }
  }
}

