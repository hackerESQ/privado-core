/*
 * This file is part of Privado OSS.
 *
 * Privado is an open source static code analysis tool to discover data flows in the code.
 * Copyright (C) 2022 Privado, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * For more information, contact support@privado.ai
 */

package ai.privado.entrypoint

import ai.privado.cache.{AppCache, Environment, RuleCache}
import ai.privado.exporter.JSONExporter
import ai.privado.metric.MetricHandler
import ai.privado.model._
import ai.privado.passes.config.PropertiesFilePass
import ai.privado.semantic.Language._
import ai.privado.model.Constants.{outputDirectoryName, outputFileName}
import ai.privado.utility.Utilities.isValidRule
import better.files.File
import io.circe.Json
import io.circe.yaml.parser
import io.joern.javasrc2cpg.{Config, JavaSrc2Cpg}
import io.joern.joerncli.DefaultOverlays
import io.shiftleft.codepropertygraph
import io.shiftleft.codepropertygraph.generated.Languages
import io.shiftleft.semanticcpg.language._
import org.slf4j.LoggerFactory

import scala.sys.exit
import scala.util.{Failure, Success, Try}

object ScanProcessor extends CommandProcessor {
  private val logger = LoggerFactory.getLogger(this.getClass)

  def parseRules(rulesPath: String): ConfigAndRules = {
    logger.trace(s"parsing rules from -> '${rulesPath}'")
    val ir: File = {
      // e.g. rulesPath = /home/pandurang/projects/rules-home/
      try File(rulesPath)
      catch {
        case ex: Throwable =>
          logger.debug("File error: ", ex)
          logger.error(s"Exception while processing rules on path $rulesPath")
          exit(1)
      }
    }
    val parsedRules =
      try
        ir.listRecursively
          .filter(f => f.extension == Some(".yaml") || f.extension == Some(".YAML"))
          .map(file => {
            // e.g. fullPath = /home/pandurang/projects/rules-home/rules/sources/accounts.yaml
            val fullPath = file.pathAsString
            logger.trace(s"parsing -> '${fullPath}'")
            // e.g. relPath = rules/sources/accounts
            val relPath  = fullPath.substring(ir.pathAsString.length + 1).split("\\.").head
            val pathTree = relPath.split("/")
            parser.parse(file.contentAsString) match {
              case Right(json) =>
                import ai.privado.model.CirceEnDe._
                json.as[ConfigAndRules] match {
                  case Right(configAndRules) =>
                    configAndRules.copy(
                      exclusions = configAndRules.exclusions.map(x =>
                        x.copy(
                          file = fullPath,
                          categoryTree = pathTree,
                          language = Language.withNameWithDefault(pathTree.last)
                        )
                      ),
                      sources = configAndRules.sources
                        .filter(rule => isValidRule(rule.patterns.head, rule.id, fullPath))
                        .map(x =>
                          x.copy(
                            file = fullPath,
                            catLevelOne = CatLevelOne.withNameWithDefault(pathTree.apply(1)),
                            categoryTree = pathTree,
                            nodeType = NodeType.REGULAR
                          )
                        ),
                      sinks = configAndRules.sinks
                        .filter(rule => isValidRule(rule.patterns.head, rule.id, fullPath))
                        .map(x =>
                          x.copy(
                            file = fullPath,
                            catLevelOne = CatLevelOne.withNameWithDefault(pathTree.apply(1)),
                            catLevelTwo = pathTree.apply(2),
                            categoryTree = pathTree,
                            language = Language.withNameWithDefault(pathTree.last),
                            nodeType = NodeType.withNameWithDefault(pathTree.apply(3))
                          )
                        ),
                      collections = configAndRules.collections
                        .filter(rule => isValidRule(rule.patterns.head, rule.id, fullPath))
                        .map(x =>
                          x.copy(
                            file = fullPath,
                            catLevelOne = CatLevelOne.withNameWithDefault(pathTree.apply(1)),
                            categoryTree = pathTree,
                            nodeType = NodeType.REGULAR
                          )
                        ),
                      policies = configAndRules.policies.map(x => x.copy(file = fullPath, categoryTree = pathTree)),
                      semantics = configAndRules.semantics.map(x =>
                        x.copy(
                          file = fullPath,
                          categoryTree = pathTree,
                          language = Language.withNameWithDefault(pathTree.last)
                        )
                      )
                    )
                  case Left(error) =>
                    logger.error("Error while parsing this file -> '" + fullPath)
                    logger.error("ERROR : " + error)
                    ConfigAndRules(
                      List[RuleInfo](),
                      List[RuleInfo](),
                      List[RuleInfo](),
                      List[PolicyOrThreat](),
                      List[PolicyOrThreat](),
                      List[RuleInfo](),
                      List[Semantic]()
                    )
                }
              case Left(error) =>
                logger.error("Error while parsing this file -> '" + fullPath)
                logger.error("ERROR : " + error)
                ConfigAndRules(
                  List[RuleInfo](),
                  List[RuleInfo](),
                  List[RuleInfo](),
                  List[PolicyOrThreat](),
                  List[PolicyOrThreat](),
                  List[RuleInfo](),
                  List[Semantic]()
                )
            }
          })
          .reduce((a, b) =>
            a.copy(
              sources = a.sources ++ b.sources,
              sinks = a.sinks ++ b.sinks,
              collections = a.collections ++ b.collections,
              policies = a.policies ++ b.policies,
              exclusions = a.exclusions ++ b.exclusions,
              threats = a.threats ++ b.threats,
              semantics = a.semantics ++ b.semantics
            )
          )
      catch {
        case ex: Throwable =>
          logger.debug("File error: ", ex)
          logger.error(s"Rules path $rulesPath is not accessible")
          exit(1)
      }
    parsedRules
  }

  def processRules(): ConfigAndRules = {
    var internalConfigAndRules =
      ConfigAndRules(
        List[RuleInfo](),
        List[RuleInfo](),
        List[RuleInfo](),
        List[PolicyOrThreat](),
        List[PolicyOrThreat](),
        List[RuleInfo](),
        List[Semantic]()
      )
    if (!config.ignoreInternalRules) {
      internalConfigAndRules = parseRules(config.internalConfigPath.head)
      RuleCache.setInternalRules(internalConfigAndRules)

      try {
        AppCache.privadoVersionMain = File((s"${config.internalConfigPath.head}/version.txt")).contentAsString
      } catch {
        case _: Exception =>
          AppCache.privadoVersionMain = Constants.notDetected
      }
      println(s"Privado Main Version: ${AppCache.privadoVersionMain}")
    }
    var externalConfigAndRules =
      ConfigAndRules(
        List[RuleInfo](),
        List[RuleInfo](),
        List[RuleInfo](),
        List[PolicyOrThreat](),
        List[PolicyOrThreat](),
        List[RuleInfo](),
        List[Semantic]()
      )
    if (config.externalConfigPath.nonEmpty) {
      externalConfigAndRules = parseRules(config.externalConfigPath.head)
    }
    /*
     * NOTE: We want to override the external rules over internal in case of duplicates by id.
     * While concatenating two lists (internal and external) and get the distinct list of elements.
     * Elements from the first collection will be kept and elements from second collection will be discarded.
     *
     * e.g
     * val sources     = externalRules.sources ++ internalRules.sources
     * sources.distinctBy(_.id) - this will return unique list of elements duplicated by id.
     * In case of duplicates it will keep the elements from "externalRules.sources".
     * We don't know the internal logic. We came to this conclusion based on testing few samples.
     */
    val exclusions  = externalConfigAndRules.exclusions ++ internalConfigAndRules.exclusions
    val sources     = externalConfigAndRules.sources ++ internalConfigAndRules.sources
    val sinks       = externalConfigAndRules.sinks ++ internalConfigAndRules.sinks
    val collections = externalConfigAndRules.collections ++ internalConfigAndRules.collections
    val policies    = externalConfigAndRules.policies ++ internalConfigAndRules.policies
    val threats     = externalConfigAndRules.threats ++ internalConfigAndRules.threats
    val semantics   = externalConfigAndRules.semantics ++ internalConfigAndRules.semantics
    val mergedRules =
      ConfigAndRules(
        sources = sources.distinctBy(_.id),
        sinks = sinks.distinctBy(_.id),
        collections = collections.distinctBy(_.id),
        policies = policies.distinctBy(_.id),
        exclusions = exclusions.distinctBy(_.id),
        threats = threats.distinctBy(_.id),
        semantics = semantics.distinctBy(_.signature)
      )
    logger.trace(mergedRules.toString)
    logger.info("Caching rules")
    RuleCache.setRule(mergedRules)
    println("Configuration parsed...")

    RuleCache.internalPolicies.addAll(internalConfigAndRules.policies.map(policy => (policy.id)))
    RuleCache.internalPolicies.addAll(internalConfigAndRules.threats.map(threat => (threat.id)))
    MetricHandler.metricsData("noOfRulesUsed") = {
      Json.fromInt(
        mergedRules.sources.size +
          mergedRules.sinks.size +
          mergedRules.collections.size +
          mergedRules.policies.size +
          mergedRules.exclusions.size
      )
    }

    mergedRules
  }
  override def process(): Either[String, Unit] = {
    println(s"Privado CLI Version: ${Environment.privadoVersionCli.getOrElse(Constants.notDetected)}")
    println(s"Privado Core Version: ${Environment.privadoVersionCore}")
    processCPG(processRules())
  }

  private def checkJavaSourceCodePresent(sourcePath: String): Boolean = {
    logger.trace(s"parsing rules from -> '${sourcePath}'")
    val sourceLocation: File = {
      try File(sourcePath)
      catch {
        case ex: Throwable =>
          logger.debug("File error: ", ex)
          logger.error(s"Exception while reading source location '$sourcePath'")
          exit(1)
      }
    }
    sourceLocation.listRecursively.count(f => f.extension == Some(".java") || f.extension == Some(".JAVA")) > 0
  }
  def processCPG(processedRules: ConfigAndRules): Either[String, Unit] = {
    val sourceRepoLocation = config.sourceLocation.head
    // Setting up the application cache
    AppCache.init(sourceRepoLocation)
    import io.joern.console.cpgcreation.guessLanguage
    println("Guessing source code language...")
    val xtocpg = guessLanguage(sourceRepoLocation) match {
      case Some(lang) =>
        if (!(lang == Languages.JAVASRC || lang == Languages.JAVA)) {
          if (checkJavaSourceCodePresent(sourceRepoLocation)) {
            println(s"We detected presence of 'Java' code base along with other major language code base '${lang}'.")
            println(s"However we only support 'Java' code base scanning as of now.")
          } else {
            println(s"As of now we only support privacy code scanning for 'Java' code base.")
            println(s"We detected this code base of '${lang}'.")
            exit(1)
          }
        } else {
          println(s"Detected language 'Java' ")
        }
        createJavaCpg(sourceRepoLocation, lang)
      case _ => {
        logger.error("Unable to detect language! Is it supported yet?")
        Failure(new RuntimeException("Unable to detect language!"))
      }
    }
    xtocpg match {
      case Success(cpgWithoutDataflow) => {
        new PropertiesFilePass(cpgWithoutDataflow, sourceRepoLocation).createAndApply()
        logger.info("Applying default overlays")
        cpgWithoutDataflow.close()
        val cpg = DefaultOverlays.create("cpg.bin")
        logger.info("=====================")

        // Run tagger
        println("Tagging source code with rules...")
        cpg.runTagger(processedRules)
        println("Finding source to sink flow of data...")
        val dataflowMap = cpg.dataflow

        println("Brewing result...")
        MetricHandler.setScanStatus(true)
        // Exporting
        JSONExporter.fileExport(cpg, outputFileName, sourceRepoLocation, dataflowMap) match {
          case Left(err) =>
            MetricHandler.otherErrorsOrWarnings.addOne(err)
            Left(err)
          case Right(_) =>
            println(s"Successfully exported output to '${AppCache.localScanPath}/$outputDirectoryName' folder")
            logger.debug(
              s"Total Sinks identified : ${cpg.tag.where(_.nameExact(Constants.catLevelOne).valueExact(CatLevelOne.SINKS.name)).call.tag.nameExact(Constants.id).value.toSet}"
            )
            Right(())
        }
      }

      case Failure(exception) => {
        logger.error("Error while parsing the source code!")
        logger.debug("Error : ", exception)
        MetricHandler.setScanStatus(false)
        Left("Error while parsing the source code: " + exception.toString)
      }
    }
  }

  /** Create cpg using Java Language
    * @param sourceRepoLocation
    * @param lang
    * @return
    */
  private def createJavaCpg(sourceRepoLocation: String, lang: String): Try[codepropertygraph.Cpg] = {
    MetricHandler.metricsData("language") = Json.fromString(lang)
    println(s"Processing source code using ${Languages.JAVASRC} engine")
    if (!config.skipDownloadDependencies)
      println("Downloading dependencies and Parsing source code...")
    else
      println("Parsing source code...")
    val cpgconfig =
      Config(inputPath = sourceRepoLocation, fetchDependencies = !config.skipDownloadDependencies)
    JavaSrc2Cpg().createCpg(cpgconfig)
  }

  override var config: PrivadoInput = _
}
