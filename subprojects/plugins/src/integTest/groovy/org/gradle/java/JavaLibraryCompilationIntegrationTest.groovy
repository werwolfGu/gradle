/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.java

import org.codehaus.groovy.control.CompilerConfiguration
import org.gradle.integtests.fixtures.AbstractIntegrationSpec

import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class JavaLibraryCompilationIntegrationTest extends AbstractIntegrationSpec {

    def "project can declare an API dependency"() {
        given:
        subproject('a') {
            'build.gradle'('''
                apply plugin: 'java-library'
                dependencies {
                    api project(':b')
                }
            ''')
            src {
                main {
                    java {
                        'ToolImpl.java'('public class ToolImpl implements Tool { public void execute() {} }')
                    }
                }
            }
        }

        subproject('b') {
            'build.gradle'('''
                apply plugin: 'java-library'
            ''')
            src {
                main {
                    java {
                        'Tool.java'('public interface Tool { void execute(); }')
                    }
                }
            }
        }

        when:
        succeeds 'a:compileJava'

        then:
        executedAndNotSkipped ':b:compileJava'
        notExecuted ':b:processResources', ':b:classes', ':b:jar'
    }

    def "uses the default configuration when producer is not a library"() {
        given:
        subproject('a') {
            'build.gradle'('''
                apply plugin: 'java-library'
                dependencies {
                    api project(':b')
                }
            ''')
            src {
                main {
                    java {
                        'ToolImpl.java'('public class ToolImpl implements Tool { public void execute() {} }')
                    }
                }
            }
        }

        subproject('b') {
            'build.gradle'('''
                apply plugin: 'java'
            ''')
            src {
                main {
                    java {
                        'Tool.java'('public interface Tool { void execute(); }')
                    }
                }
            }
        }

        when:
        succeeds 'a:compileJava'

        then:
        executedAndNotSkipped ':b:compileJava', ':b:classes', ':b:jar'
        skipped ':b:processResources'
    }

    def "uses the API configuration when compiling a project against a library"() {
        given:
        subproject('a') {
            'build.gradle'('''
                apply plugin: 'java'
                dependencies {
                    compile project(':b')
                }
            ''')
            src {
                main {
                    java {
                        'ToolImpl.java'('public class ToolImpl implements Tool { public void execute() {} }')
                    }
                }
            }
        }

        subproject('b') {
            'build.gradle'('''
                apply plugin: 'java-library'
            ''')
            src {
                main {
                    java {
                        'Tool.java'('public interface Tool { void execute(); }')
                    }
                }
            }
        }

        when:
        succeeds 'a:compileJava'

        then:
        executedAndNotSkipped ':b:compileJava'
        notExecuted ':b:processResources', ':b:classes', ':b:jar'
    }

    def "recompiles consumer if API dependency of producer changed"() {
        publishSharedV1()
        publishSharedV11()

        given:
        subproject('a') {
            'build.gradle'("""
                apply plugin: 'java'

                repositories {
                    maven { url '$mavenRepo.uri' }
                }

                dependencies {
                    compile project(':b')
                }
            """)
            src {
                main {
                    java {
                        'ToolImpl.java'('public class ToolImpl implements Tool { public void execute() {} }')
                    }
                }
            }
        }

        subproject('b') {
            'build.gradle'("""
                apply plugin: 'java-library'

                repositories {
                    maven { url '$mavenRepo.uri' }
                }

                dependencies {
                    api 'org.gradle.test:shared:1.0'
                }
            """)
            src {
                main {
                    java {
                        'Tool.java'('public interface Tool { void execute(); }')
                    }
                }
            }
        }

        when:
        succeeds 'a:compileJava'

        then:
        executedAndNotSkipped ':a:compileJava', ':b:compileJava'
        notExecuted ':b:processResources', ':b:classes', ':b:jar'

        when:
        file('b/build.gradle').text = file('b/build.gradle').text.replace(/api 'org.gradle.test:shared:1.0'/, '''
            // update an API dependency
            api 'org.gradle.test:shared:1.1'
        ''')

        then:
        succeeds ':a:compileJava', 'a:compileJava'
        executedAndNotSkipped ':b:compileJava'
        notExecuted ':b:processResources', ':b:classes', ':b:jar'
    }

    def "doesn't recompile consumer if implementation dependency of producer changed"() {
        publishSharedV1()
        publishSharedV11()

        given:
        subproject('a') {
            'build.gradle'("""
                apply plugin: 'java'

                dependencies {
                    compile project(':b')
                }
            """)
            src {
                main {
                    java {
                        'ToolImpl.java'('public class ToolImpl implements Tool { public void execute() {} }')
                    }
                }
            }
        }

        subproject('b') {
            'build.gradle'("""
                apply plugin: 'java-library'

                repositories {
                    maven { url '$mavenRepo.uri' }
                }

                dependencies {
                    implementation 'org.gradle.test:shared:1.0'
                }
            """)
            src {
                main {
                    java {
                        'Tool.java'('public interface Tool { void execute(); }')
                    }
                }
            }
        }

        when:
        succeeds 'a:compileJava'

        then:
        executedAndNotSkipped ':a:compileJava', ':b:compileJava'
        notExecuted ':b:processResources', ':b:classes', ':b:jar'

        when:
        file('b/build.gradle').text = file('b/build.gradle').text.replace(/implementation 'org.gradle.test:shared:1.0'/, '''
            // update an API dependency
            implementation 'org.gradle.test:shared:1.1'
        ''')

        then:
        succeeds 'a:compileJava'
        executedAndNotSkipped ':b:compileJava'
        notExecuted ':b:processResources', ':b:classes', ':b:jar'
        skipped ':a:compileJava'
    }

    private void subproject(String name, @DelegatesTo(value=FileTreeBuilder, strategy = Closure.DELEGATE_FIRST) Closure<Void> config) {
        file("settings.gradle") << "include '$name'\n"
        def subprojectDir = file(name)
        subprojectDir.mkdirs()
        FileTreeBuilder builder = new FileTreeBuilder(subprojectDir)
        config.setDelegate(builder)
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
    }

    private void publishSharedV1() {
        buildSharedJar('1.0', 'class Foo {}')
    }

    private void publishSharedV11() {
        buildSharedJar('1.1', 'class Foo { int x }')
    }

    private void buildSharedJar(String version, String classBody) {
        def jar = mavenRepo.module('org.gradle.test', 'shared', version).publish().artifactFile
        def configuration = new CompilerConfiguration()
        def bytes = null
        configuration.setBytecodePostprocessor { String name, byte[] original ->
            bytes = original
        }
        def gcl = new GroovyClassLoader(this.class.classLoader, configuration)
        gcl.parseClass(classBody)
        def jos = new JarOutputStream(jar.newOutputStream())
        jos.putNextEntry(new JarEntry("Foo.class"))
        jos.write(bytes)
        jos.closeEntry()
        jos.close()
        jar
    }
}
