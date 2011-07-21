/*
 * Copyright 2010 the original author or authors.
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
package org.gradle.plugins.ide.eclipse.model

import spock.lang.Specification
import org.gradle.plugins.ide.eclipse.model.internal.FileReferenceFactory
import org.gradle.util.Matchers

/**
 * @author Hans Dockter
 */

class VariableTest extends Specification {
    final static String XML_TEXT = '''
                <classpathentry exported="true" kind="var" path="/GRADLE_CACHE/ant.jar" sourcepath="/GRADLE_CACHE/ant-src.jar">
                    <attributes>
                        <attribute name="org.eclipse.jdt.launching.CLASSPATH_ATTR_LIBRARY_PATH_ENTRY" value="mynative"/>
                        <attribute name="javadoc_location" value="jar:file:/GRADLE_CACHE/ant-javadoc.jar!/path"/>
                    </attributes>
                    <accessrules>
                        <accessrule kind="nonaccessible" pattern="secret**"/>
                    </accessrules>
                </classpathentry>'''
    final fileReferenceFactory = new FileReferenceFactory()

    def canReadFromXml() {
        when:
        Variable variable = new Variable(new XmlParser().parseText(XML_TEXT), fileReferenceFactory)

        then:
        variable == createVariable()
    }

    def canWriteToXml() {
        Node rootNode = new Node(null, 'root')

        when:
        createVariable().appendNode(rootNode)

        then:
        new Variable(rootNode.classpathentry[0], fileReferenceFactory) == createVariable()
    }

    def equality() {
        Variable variable = createVariable()
        Variable same = createVariable()
        Variable different = createVariable()
        different.path = '/other'

        expect:
        variable Matchers.strictlyEqual(same)
        variable != different
    }

    private Variable createVariable() {
        Variable variable = new Variable(fileReferenceFactory.fromVariablePath('/GRADLE_CACHE/ant.jar'))
        variable.exported = true
        variable.nativeLibraryLocation = 'mynative'
        variable.accessRules += [new AccessRule('nonaccessible', 'secret**')]
        variable.sourcePath = fileReferenceFactory.fromVariablePath("/GRADLE_CACHE/ant-src.jar")
        variable.javadocPath = fileReferenceFactory.fromVariablePath("jar:file:/GRADLE_CACHE/ant-javadoc.jar!/path")
        return variable
    }
}