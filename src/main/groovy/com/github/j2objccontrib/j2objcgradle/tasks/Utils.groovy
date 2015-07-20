/*
 * Copyright (c) 2015 the authors of j2objc-gradle (see AUTHORS file)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.j2objccontrib.j2objcgradle.tasks

import com.google.common.annotations.VisibleForTesting
import groovy.transform.CompileStatic
import groovy.transform.TypeCheckingMode
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType
import groovy.util.logging.Slf4j
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Nullable
import org.gradle.api.Project
import org.gradle.api.file.CopySpec
import org.gradle.api.file.FileCollection
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.WorkResult
import org.gradle.process.ExecResult
import org.gradle.process.ExecSpec
import org.gradle.process.internal.ExecException

import java.util.regex.Matcher

/**
 * Internal utilities supporting plugin implementation.
 */
// Without access to the project, logging is performed using the
// static 'log' variable added during decoration with this annotation.
@Slf4j
@CompileStatic
class Utils {
    // TODO: ideally bundle j2objc binaries with plugin jar and load at runtime with
    // TODO: ClassLoader.getResourceAsStream(), extract, chmod and then execute

    static boolean isWindows() {
        return System.getProperty('os.name').toLowerCase().contains('windows')
    }

    static void throwIfNoJavaPlugin(Project proj) {
        if (!proj.plugins.hasPlugin('java')) {
            String message =
                    "j2objc plugin didn't find the 'java' plugin in the '${proj.name}' project.\n" +
                    "This is a requirement for using j2objc. Please see usage information at:\n" +
                    "https://github.com/j2objc-contrib/j2objc-gradle/#usage"
            throw new InvalidUserDataException(message)
        }
    }

    static String getLocalProperty(Project proj, String key, String defaultValue = null) {
        File localPropertiesFile = new File(proj.rootDir, 'local.properties')
        String result = defaultValue
        if (localPropertiesFile.exists()) {
            Properties localProperties = new Properties()
            localPropertiesFile.withInputStream {
                localProperties.load it
            }
            result = localProperties.getProperty('j2objc.' + key, defaultValue)
        }
        return result
    }

    // MUST be used only in @Input getJ2objcHome() methods to ensure UP-TO-DATE checks are correct
    // @Input getJ2objcHome() method can be used freely inside the task action
    static String j2objcHome(Project proj) {
        String result = getLocalProperty(proj, 'home')
        if (result == null) {
            result = System.getenv('J2OBJC_HOME')
        }
        if (result == null) {
            String message =
                    "j2objc home not set, this should be configured either:\n" +
                    "1) in a 'local.properties' file in the project root directory as:\n" +
                    "   j2objc.home=/PATH/TO/J2OBJC/DISTRIBUTION\n" +
                    "2) as the J2OBJC_HOME system environment variable\n" +
                    "\n" +
                    "If both are configured the value in the properties file will be used.\n" +
                    "\n" +
                    "It must be the path of the unzipped j2objc distribution. Download releases here:\n" +
                    "https://github.com/google/j2objc/releases"
            throw new InvalidUserDataException(message)
        }
        if (!proj.file(result).exists()) {
            String message = "j2objc directory not found, expected location: ${result}"
            throw new InvalidUserDataException(message)
        }
        return result
    }

    // Reads properties file and arguments from translateArgs (last argument takes precedence)
    //   --prefixes dir/prefixes.properties --prefix com.ex.dir=Short --prefix com.ex.dir2=Short2
    // TODO: separate this out to a distinct argument that's added to translateArgs
    // TODO: @InputFile conversion for this
    static Properties packagePrefixes(Project proj, List<String> translateArgs) {
        Properties props = new Properties()
        String joinedTranslateArgs = translateArgs.join(' ')
        Matcher matcher = (joinedTranslateArgs =~ /--prefix(|es)\s+(\S+)/)
        int start = 0
        while (matcher.find(start)) {
            start = matcher.end()
            Properties newProps = new Properties()
            String argValue = matcher.group(2)
            if (matcher.group(1) == "es") {
                // --prefixes prefixes.properties
                // trailing space confuses FileInputStream
                String prefixesPath = argValue.trim()
                log.debug "Loading prefixesPath: $prefixesPath"
                newProps.load(new FileInputStream(proj.file(prefixesPath).path))
            } else {
                // --prefix com.example.dir=CED
                newProps.load(new StringReader(argValue.trim()))
            }
            props.putAll(newProps)
        }

        log.debug 'Package Prefixes: http://j2objc.org/docs/Package-Prefixes.html'
        for (key in props.keys()) {
            log.debug "Package Prefix Property: $key : ${props.getProperty((String) key)}"
        }

        return props
    }

    /*
     * Throws exception if two filenames match, even if in distinct directories.
     * This is important for referencing files with Xcode.
     */

    static void filenameCollisionCheck(FileCollection files) {
        HashMap<String, File> nameMap = [:]
        for (file in files) {
            log.debug "CollisionCheck: ${file.name}, ${file.absolutePath}"
            if (nameMap.containsKey(file.name)) {
                File prevFile = nameMap.get(file.name)
                String message =
                        "File name collision detected:\n" +
                        "  ${prevFile.path}\n" +
                        "  ${file.path}\n" +
                        "\n" +
                        "To disable this check (which may overwrite files), modify build.gradle:\n" +
                        "\n" +
                        "j2objcConfig {\n" +
                        "    filenameCollisionCheck false\n" +
                        "}\n"
                throw new InvalidUserDataException(message)
            }
            nameMap.put(file.name, file)
        }
    }

    // Retrieves the configured source directories from the Java plugin SourceSets.
    // This includes the files for all Java source within these directories.
    static SourceDirectorySet srcSet(Project proj, String sourceSetName, String fileType) {
        throwIfNoJavaPlugin(proj)

        assert fileType == 'java' || fileType == 'resources'
        assert sourceSetName == 'main' || sourceSetName == 'test'
        JavaPluginConvention javaConvention = proj.getConvention().getPlugin(JavaPluginConvention)
        SourceSet sourceSet = javaConvention.sourceSets.findByName(sourceSetName)
        // For standard fileTypes 'java' and 'resources,' per contract this cannot be null.
        SourceDirectorySet srcDirSet = fileType == 'java' ? sourceSet.java : sourceSet.resources
        return srcDirSet
    }

    // Add list of java path to a FileCollection as a FileTree
    static FileCollection javaTrees(Project proj, List<String> treePaths) {
        FileCollection files = proj.files()
        treePaths.each { String treePath ->
            log.debug "javaTree: $treePath"
            files = files.plus(
                    proj.files(proj.fileTree(dir: treePath, includes: ["**/*.java"])))
        }
        return files
    }

    static List<String> j2objcLibs(String j2objcHome,
                                   List<String> libraries) {
        return libraries.collect { String library ->
            return "$j2objcHome/lib/$library"
        }
    }

    // Convert FileCollection to joined path arg, e.g. "src/Some.java:src/Another.java"
    static String joinedPathArg(FileCollection files) {
        String[] paths = []
        files.each { File file ->
            paths += file.path
        }
        return paths.join(':')
    }

    // Convert regex to string for display, wrapping it with /.../
    // From Groovy-Lang: "Only forward slashes need to be escaped with a backslash"
    // http://docs.groovy-lang.org/latest/html/documentation/#_slashy_string
    static String escapeSlashyString(String regex) {
        return '/' + regex.replace('/', '\\/') + '/'
    }

    // Matches regex, return first match as string, must have >1 capturing group
    // Return first capture group, comparing stderr first then stdout
    // Returns null for no match
    static String matchRegexOutputs(
            ByteArrayOutputStream stdout,
            ByteArrayOutputStream stderr,
            @Nullable String regex) {

        if (regex == null) {
            return null
        }

        Matcher stdMatcher = (stdout.toString() =~ regex)
        Matcher errMatcher = (stderr.toString() =~ regex)
        // Requires a capturing group in the regex
        String assertFailMsg = "matchRegexOutputs must have '(...)' capture group, regex: '$regex'"
        assert stdMatcher.groupCount() >= 1, assertFailMsg
        assert errMatcher.groupCount() >= 1, assertFailMsg

        if (errMatcher.find()) {
            return errMatcher.group(1)
        }
        if (stdMatcher.find()) {
            return stdMatcher.group(1)
        }

        return null
    }

    /**
     * Executes command line and returns result.
     *
     * Throws exception if command fails or non-null regex doesn't match stdout or stderr.
     * The exceptions have detailed information on command line, stdout, stderr and failure cause.
     *
     * @param proj Runs proj.exec {...} method
     * @param stdout To capture standard output
     * @param stderr To capture standard output
     * @param matchRegexOutputsRequired Throws exception if stdout/stderr don't match regex.
     *        Matches each OutputStream separately, not in combination. Ignored if null.
     * @param closure ExecSpec type for proj.exec {...} method
     * @return ExecResult from the method
     */
    // See http://melix.github.io/blog/2014/01/closure_param_inference.html
    //
    // TypeCheckingMode.SKIP allows Project.exec to be mocked via metaclass in TestingUtils.groovy.
    // ClosureParams allows type checking to enforce that the first param ('it') to the Closure is
    // an ExecSpec. DelegatesTo allows type checking to enforce that the delegate is ExecSpec.
    // Together this emulates the functionality of ExecSpec.with(Closure).
    //
    // We are using a non-API-documented assumption that the delegate is an ExecSpec.  If the
    // implementation changes, this will fail at runtime.
    // TODO: In Gradle 2.5, we can switch to strongly-typed Actions, like:
    // https://docs.gradle.org/2.5/javadoc/org/gradle/api/Project.html#copy(org.gradle.api.Action)
    @CompileStatic(TypeCheckingMode.SKIP)
    static ExecResult projectExec(
            Project proj,
            ByteArrayOutputStream stdout,
            ByteArrayOutputStream stderr,
            @Nullable String matchRegexOutputsRequired,
            @ClosureParams(value = SimpleType.class, options = "org.gradle.process.ExecSpec")
            @DelegatesTo(ExecSpec)
                    Closure closure) {

        ExecSpec execSpec = null
        ExecResult execResult
        boolean execSucceeded = false

        try {
            execResult = proj.exec {
                execSpec = delegate as ExecSpec
                (execSpec).with closure
            }
            execSucceeded = true
            if (matchRegexOutputsRequired) {
                if (!matchRegexOutputs(stdout, stderr, matchRegexOutputsRequired)) {
                    // Exception thrown here to output command line
                    throw new InvalidUserDataException(
                            'Unable to find expected expected output in stdout or stderr\n' +
                            'Failed Regex Match: ' + escapeSlashyString(matchRegexOutputsRequired))
                }
            }

        } catch (Exception exception) {

            // ExecException is most common, which indicates "non-zero exit"
            // Add command line and stderr to make the error message more useful
            // Chain to the original ExecException for complete stack trace
            String exceptionMsg = ''
            if (execSucceeded) {
                exceptionMsg += 'Command Line Succeeded (failure cause listed below):\n'
            } else {
                exceptionMsg += 'Command Line Failed:\n'
            }
            exceptionMsg +=
                    execSpec.getCommandLine().join(' ') + '\n' +
                    // The command line can be long, so put more important details at the end
                    'Caused by:\n' +
                    exception.toString() + '\n' +
                    stdOutAndErrToLogString(stdout, stderr)

            throw new InvalidUserDataException(exceptionMsg, exception)
        }

        logDebugExecSpecOutput(stdout, stderr, execSpec)

        return execResult
    }

    @VisibleForTesting
    static void logDebugExecSpecOutput(
            ByteArrayOutputStream stdout, ByteArrayOutputStream stderr, ExecSpec execSpec) {

        if (execSpec == null) {
            log.debug('execSpec is null')
            return
        }

        log.debug('Command Line:\n' + execSpec.getCommandLine().join(' '))

        String stdoutStr = stdout.toString()
        String stderrStr = stderr.toString()
        if (!stdoutStr.empty) {
            log.debug(stdoutStr)
        }
        if (!stderrStr.empty) {
            log.debug(stderrStr)
        }
    }

    static String stdOutAndErrToLogString(ByteArrayOutputStream stdout, ByteArrayOutputStream stderr) {
        return 'Standard Output:\n' +
                stdout.toString() + '\n' +
                'Error Output:\n' +
                stderr.toString()
    }

    static boolean isProjectExecNonZeroExit(Exception exception) {
        return (exception instanceof InvalidUserDataException) &&
               // TODO: improve indentification of non-zero exits?
               (exception?.getCause() instanceof ExecException)
    }

    // See projectExec for explanation of the annotations.
    @CompileStatic(TypeCheckingMode.SKIP)
    static WorkResult projectCopy(Project proj,
                                  @ClosureParams(value = SimpleType.class, options = "org.gradle.api.file.CopySpec")
                                  @DelegatesTo(CopySpec)
                                          Closure closure) {
        proj.copy {
            (delegate as CopySpec).with closure
        }
    }
}
