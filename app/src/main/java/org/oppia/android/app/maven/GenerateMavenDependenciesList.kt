package org.oppia.android.app.maven

import com.google.protobuf.MessageLite
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.io.FileInputStream
import org.oppia.android.app.maven.maveninstall.MavenListDependency
import org.oppia.android.app.maven.maveninstall.MavenListDependencyTree
import org.oppia.android.app.maven.proto.MavenDependencyList
import org.oppia.android.app.maven.proto.MavenDependency
import org.oppia.android.app.maven.proto.License
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import org.oppia.android.app.maven.proto.OriginOfLicenses
import org.oppia.android.app.maven.proto.PrimaryLinkType
import org.oppia.android.app.maven.proto.SecondaryLinkType

private const val WAIT_PROCESS_TIMEOUT_MS = 60_000L
private const val LICENSES_TAG = "<licenses>"
private const val LICENSES_CLOSE_TAG = "</licenses>"
private const val LICENSE_TAG = "<license>"
private const val NAME_TAG = "<name>"
private const val URL_TAG = "<url>"

//var backupLicenseLinksList: MutableSet<BackupLicense> = mutableSetOf<BackupLicense>()
var backupLicenseDepsList: MutableList<String> = mutableListOf<String>()

var bazelQueryDepsNames: MutableList<String> = mutableListOf<String>()
var mavenInstallDependencyList: MutableList<MavenListDependency>? =
  mutableListOf<MavenListDependency>()
var finalDependenciesList = mutableListOf<MavenListDependency>()
var parsedArtifactsList = mutableListOf<String>()

val linksSet = mutableSetOf<String>()
val noLicenseSet = mutableSetOf<String>()

private var countInvalidPomUrl = 0
var countDepsWithoutLicenseLinks = 0

var scriptFailed = false

var rootPath: String = ""

fun printMessage(message: String) {
  println("****************")
  println(message)
  println("****************\n")
}

// Utility function to write all the mavenListDependencies of the bazelQueryDepsList.
fun showBazelQueryDepsList() {
  val bazelListFile = File("/home/prayutsu/opensource/oppia-android/bazel_list.txt")
  bazelListFile.printWriter().use { writer ->
    var count = 0
    bazelQueryDepsNames.forEach {
      writer.print("${count++} ")
      writer.println(it)
    }
  }
}

// Utility function to write all the mavenListDependencies of the parsedArtifactsList.
fun showFinalDepsList() {
  val finalDepsFile = File("/home/prayutsu/opensource/oppia-android/parsed_list.txt")
  finalDepsFile.printWriter().use { writer ->
    var count = 0
    parsedArtifactsList.forEach {
      writer.print("${count++} ")
      writer.println(it)
    }
  }
}

fun main(args: Array<String>) {
  if (args.isNotEmpty()) println(args[0])
  rootPath = args[0]
  runMavenRePinCommand(args[0])
  runBazelQueryCommand(args[0])
  readMavenInstall()
  val savedDependenciesList = retrieveMavenDependencyList()
  val latestDependenciesList = getLicenseLinksFromPOM()

  writeTextProto(args[1], latestDependenciesList)
  val finalList = updateMavenDependenciesList(
    savedDependenciesList,
    latestDependenciesList.mavenDependencyListList
  )
  val licensesToBeFixed = getAllBrokenLicenses(finalList)

  if (licensesToBeFixed.isNotEmpty()) {
    println("Please provide the details of the following licenses manually:")
    licensesToBeFixed.forEach {
      println(it)
    }
    throw Exception("Licenses details are not completed.")
  }

  val dependenciesWithoutAnyLinks = getDependenciesWithNoLinks(finalList)
  if (dependenciesWithoutAnyLinks.isNotEmpty()) {
    println("Please provide the license links for the following dependencies manually:")
    dependenciesWithoutAnyLinks.forEach {
      println(it)
    }
    throw Exception("There does not exist any license links for some dependencies.")
  }

  if (scriptFailed) {
    throw Exception(
      "Script could not get license links" +
        " for all the Maven MavenListDependencies."
    )
  }
}

fun getDependenciesWithNoLinks(
  mavenDependenciesList: List<MavenDependency>
) : Set<MavenDependency> {
  val dependenciesWithoutLicenses = mutableSetOf<MavenDependency>()
  mavenDependenciesList.forEach {
    if (it.licenseList.isEmpty()) {
      dependenciesWithoutLicenses.add(it)
    }
  }
  return dependenciesWithoutLicenses
}

fun getAllBrokenLicenses(
  mavenDependenciesList: List<MavenDependency>
): Set<License> {
  val licenseSet = mutableSetOf<License>()
  mavenDependenciesList.forEach { dependency ->
    dependency.licenseList.forEach { license ->
      if (
        license.primaryLinkType == PrimaryLinkType.PRIMARY_LINK_TYPE_UNSPECIFIED ||
        license.primaryLinkType == PrimaryLinkType.UNRECOGNIZED
      ) {
        licenseSet.add(license)
      } else if (
        license.primaryLinkType == PrimaryLinkType.SCRAPE_FROM_LOCAL_COPY &&
        (license.secondaryLink.isEmpty() || license.secondaryLinkType == SecondaryLinkType.UNRECOGNIZED ||
          license.secondaryLinkType == SecondaryLinkType.SECONDARY_LINK_TYPE_UNSPECIFIED ||
          license.secondaryLicenseName.isEmpty()
        )
      ) {
        licenseSet.add(license)
      } else if (
        license.primaryLinkType == PrimaryLinkType.SHOW_LINK_ONLY &&
        (license.secondaryLink.isEmpty() || license.secondaryLinkType == SecondaryLinkType.UNRECOGNIZED ||
          license.secondaryLinkType == SecondaryLinkType.SECONDARY_LINK_TYPE_UNSPECIFIED ||
          license.secondaryLicenseName.isEmpty()
          )
      ) {
        licenseSet.add(license)
      }
    }
  }
  return licenseSet
}

fun updateMavenDependenciesList(
  savedDependenciesList: List<MavenDependency>,
  latestDependenciesList: List<MavenDependency>
): MutableList<MavenDependency> {
  val finalUpdatedList = mutableListOf<MavenDependency>()
  latestDependenciesList.forEach { it ->
    val index = savedDependenciesList.binarySearchBy(it.artifactName) { it.artifactName }
    if (index >= 0) {
      finalUpdatedList.add(savedDependenciesList[index])
    } else {
      finalUpdatedList.add(it)
    }
  }
  return finalUpdatedList
}

/**
 * Retrieves all file content checks.
 *
 * @return a list of all the FileContentChecks
 */
private fun retrieveMavenDependencyList(): List<MavenDependency> {
  return getProto(
    "maven_dependencies.pb",
    MavenDependencyList.getDefaultInstance()
  ).mavenDependencyListList.toList()
}

/**
 * Helper function to parse the textproto file to a proto class.
 *
 * @param textProtoFileName name of the textproto file to be parsed
 * @param proto instance of the proto class
 * @return proto class from the parsed textproto file
 */
private fun <T : MessageLite> getProto(textProtoFileName: String, proto: T): T {
  val protoBinaryFile = File("app/assets/$textProtoFileName")
  val builder = proto.newBuilderForType()

  // This cast is type-safe since proto guarantees type consistency from mergeFrom(),
  // and this method is bounded by the generic type T.
  @Suppress("UNCHECKED_CAST")
  val protoObj: T =
    FileInputStream(protoBinaryFile).use {
      builder.mergeFrom(it)
    }.build() as T
  return protoObj
}

fun parseArtifactName(artifactName: String): String {
  var colonIndex = artifactName.length - 1
  while (artifactName.isNotEmpty() && artifactName[colonIndex] != ':') {
    colonIndex--
  }
  val artifactNameWithoutVersion = artifactName.substring(0, colonIndex)
  val parsedArtifactNameBuilder = StringBuilder()
  for (index in artifactNameWithoutVersion.indices) {
    if (artifactNameWithoutVersion[index] == '.' || artifactNameWithoutVersion[index] == ':' ||
      artifactNameWithoutVersion[index] == '-'
    ) {
      parsedArtifactNameBuilder.append(
        '_'
      )
    } else {
      parsedArtifactNameBuilder.append(artifactNameWithoutVersion[index])
    }
  }
  return parsedArtifactNameBuilder.toString()
}

fun runBazelQueryCommand(rootPath: String) {
  val rootDirectory = File(rootPath).absoluteFile
  val bazelClient = BazelClient(rootDirectory)
  val output = bazelClient.executeBazelCommand(
    "query",
    "\'deps(deps(//:oppia)",
    "intersect",
    "//third_party/...)",
    "intersect",
    "@maven//...\'"
  )

  output.forEach { dep ->
    bazelQueryDepsNames.add(dep.substring(9, dep.length))
  }
  bazelQueryDepsNames.sort()
}

private fun readMavenInstall() {
  val mavenInstallJson =
    File("/home/prayutsu/opensource/oppia-android/third_party/maven_install.json")
  val mavenInstallJsonText =
    mavenInstallJson.inputStream().bufferedReader().use { it.readText() }

  val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
  val adapter = moshi.adapter(MavenListDependencyTree::class.java)
  val dependencyTree = adapter.fromJson(mavenInstallJsonText)
  mavenInstallDependencyList = dependencyTree?.mavenListDependencies?.dependencyList
  mavenInstallDependencyList?.sortBy { it -> it.coord }

  mavenInstallDependencyList?.forEach { dep ->
    val artifactName = dep.coord
    val parsedArtifactName = parseArtifactName(artifactName)
    if (bazelQueryDepsNames.contains(parsedArtifactName)) {
      parsedArtifactsList.add(parsedArtifactName)
      finalDependenciesList.add(dep)
    }
  }
  println("final list size = ${finalDependenciesList.size}")
  println("bazel query size = ${bazelQueryDepsNames.size}")
}

private fun getLicenseLinksFromPOM(): MavenDependencyList {
  var index = 0
  val mavenDependencyList = arrayListOf<MavenDependency>()
  finalDependenciesList.forEach {
    val url = it.url
    val pomFileUrl = url?.substring(0, url.length - 3) + "pom"
    val artifactName = it.coord
    val artifactVersion = StringBuilder()
    var lastIndex = artifactName.length - 1
    while (lastIndex >= 0 && artifactName[lastIndex] != ':') {
      artifactVersion.append(artifactName[lastIndex])
      lastIndex--
    }
    val licenseNamesFromPom = mutableListOf<String>()
    val licenseLinksFromPom = mutableListOf<String>()
    val licenseList = arrayListOf<License>()
    try {
      val pomfile = URL(pomFileUrl).openStream().bufferedReader().readText()
      val pomText = pomfile
      var cursor = -1
      if (pomText.length > 11) {
        for (index in 0..(pomText.length - 11)) {
          if (pomText.substring(index, index + 10) == LICENSES_TAG) {
            cursor = index + 9
            break
          }
        }
        if (cursor != -1) {
          var cursor2 = cursor
          while (cursor2 < (pomText.length - 12)) {
            if (pomText.substring(cursor2, cursor2 + 9) == LICENSE_TAG) {
              cursor2 += 9
              while (cursor2 < pomText.length - 6 &&
                pomText.substring(
                  cursor2,
                  cursor2 + 6
                ) != NAME_TAG
              ) {
                ++cursor2
              }
              cursor2 += 6
              val url = StringBuilder()
              val urlName = StringBuilder()
              while (pomText[cursor2] != '<') {
                urlName.append(pomText[cursor2])
                ++cursor2
              }
              while (cursor2 < pomText.length - 4 &&
                pomText.substring(
                  cursor2,
                  cursor2 + 5
                ) != URL_TAG
              ) {
                ++cursor2
              }
              cursor2 += 5
              while (pomText[cursor2] != '<') {
                url.append(pomText[cursor2])
                ++cursor2
              }
              licenseNamesFromPom.add(urlName.toString())
              licenseLinksFromPom.add(url.toString())
              licenseList.add(
                License
                  .newBuilder()
                  .setLicenseName(urlName.toString())
                  .setPrimaryLink(url.toString())
                  .setPrimaryLinkType(PrimaryLinkType.PRIMARY_LINK_TYPE_UNSPECIFIED)
                  .build()
              )
              linksSet.add(url.toString())
            } else if (pomText.substring(cursor2, cursor2 + 12) == LICENSES_CLOSE_TAG) {
              break
            }
            ++cursor2
          }
        }
      }
    } catch (e: Exception) {
      ++countInvalidPomUrl
      scriptFailed = true
      println("****************")
      val message = """
          Error : There was a problem while opening the provided link  -
          URL : $pomFileUrl")
          MavenListDependency Name : $artifactName""".trimIndent()
      printMessage(message)
      e.printStackTrace()
      exitProcess(1)
    }
    val mavenDependency = MavenDependency
      .newBuilder()
      .setIndex(index++)
      .setArtifactName(it.coord)
      .setArtifactVersion(artifactVersion.toString())
      .addAllLicense(licenseList)
      .setOriginOfLicenseValue(OriginOfLicenses.UNKNOWN_VALUE)

    mavenDependencyList.add(mavenDependency.build())
  }
  return MavenDependencyList.newBuilder().addAllMavenDependencyList(mavenDependencyList).build()
}

fun writeTextProto(
  pathToTextProto: String,
  mavenDependencyList: MavenDependencyList
) {
  val file = File(pathToTextProto)
  val list = mavenDependencyList.toString()

  file.printWriter().use { out ->
    out.println(list)
  }
}

fun runMavenRePinCommand(rootPath: String) {
  val rootDirectory = File(rootPath).absoluteFile
  val bazelClient = BazelClient(rootDirectory)
  val output = bazelClient.executeBazelRePinCommand(
    "bazel",
    "run",
    "@unpinned_maven//:pin"
  )
  println(output)
}

private class BazelClient(private val rootDirectory: File) {
  fun executeBazelCommand(
    vararg arguments: String,
    allowPartialFailures: Boolean = false
  ): List<String> {
    val result =
      executeCommand(rootDirectory, command = "bazel", *arguments, includeErrorOutput = false)
    // Per https://docs.bazel.build/versions/main/guide.html#what-exit-code-will-i-get error code of
    // 3 is expected for queries since it indicates that some of the arguments don't correspond to
    // valid targets. Note that this COULD result in legitimate issues being ignored, but it's
    // unlikely.
    val expectedExitCodes = if (allowPartialFailures) listOf(0, 3) else listOf(0)
    check(result.exitCode in expectedExitCodes) {
      "Expected non-zero exit code (not ${result.exitCode}) for command: ${result.command}." +
        "\nStandard output:\n${result.output.joinToString("\n")}" +
        "\nError output:\n${result.errorOutput.joinToString("\n")}"
    }
    return result.output
  }

  fun executeBazelRePinCommand(
    vararg arguments: String,
    allowPartialFailures: Boolean = false
  ): List<String> {
    val result =
      executeCommand(rootDirectory, command = "REPIN=1", *arguments, includeErrorOutput = false)
    // Per https://docs.bazel.build/versions/main/guide.html#what-exit-code-will-i-get error code of
    // 3 is expected for queries since it indicates that some of the arguments don't correspond to
    // valid targets. Note that this COULD result in legitimate issues being ignored, but it's
    // unlikely.
    val expectedExitCodes = if (allowPartialFailures) listOf(0, 3) else listOf(0)
    check(result.exitCode in expectedExitCodes) {
      "Expected non-zero exit code (not ${result.exitCode}) for command: ${result.command}." +
        "\nStandard output:\n${result.output.joinToString("\n")}" +
        "\nError output:\n${result.errorOutput.joinToString("\n")}"
    }
    return result.output
  }

  /**
   * Executes the specified [command] in the specified working directory [workingDir] with the
   * provided arguments being passed as arguments to the command.
   *
   * Any exceptions thrown when trying to execute the application will be thrown by this method.
   * Any failures in the underlying process should not result in an exception.
   *
   * @param includeErrorOutput whether to include error output in the returned [CommandResult],
   *     otherwise it's discarded
   * @return a [CommandResult] that includes the error code & application output
   */
  private fun executeCommand(
    workingDir: File,
    command: String,
    vararg arguments: String,
    includeErrorOutput: Boolean = true
  ): CommandResult {
    check(workingDir.isDirectory) {
      "Expected working directory to be an actual directory: $workingDir"
    }
    val assembledCommand = listOf(command) + arguments.toList()
    println(assembledCommand)
    val command = assembledCommand.joinToString(" ")
    println(command)
    val process = ProcessBuilder()
      .command("bash", "-c", command)
      .directory(workingDir)
      .redirectErrorStream(includeErrorOutput)
      .start()
    val finished = process.waitFor(WAIT_PROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    check(finished) { "Process did not finish within the expected timeout" }
    return CommandResult(
      process.exitValue(),
      process.inputStream.bufferedReader().readLines(),
      if (!includeErrorOutput) process.errorStream.bufferedReader().readLines() else listOf(),
      assembledCommand,
    )
  }
}

/** The result of executing a command using [executeCommand]. */
private data class CommandResult(
  /** The exit code of the application. */
  val exitCode: Int,
  /** The lines of output from the command, including both error & standard output lines. */
  val output: List<String>,
  /** The lines of error output, or empty if error output is redirected to [output]. */
  val errorOutput: List<String>,
  /** The fully-formed command line executed by the application to achieve this result. */
  val command: List<String>,
)
