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

package org.gradle.api.internal.artifacts.transform;

import org.gradle.api.Transformer;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.internal.artifacts.configurations.ResolutionStrategyInternal;

import java.io.File;
import java.util.List;
import java.util.Set;

class InstantiatingArtifactTransforms implements ArtifactTransforms {
    private final ResolutionStrategyInternal resolutionStrategy;
    private final ArtifactAttributeMatcher attributeMatcher;

    public InstantiatingArtifactTransforms(ResolutionStrategyInternal resolutionStrategy, ArtifactAttributeMatcher attributeMatcher) {
        this.resolutionStrategy = resolutionStrategy;
        this.attributeMatcher = attributeMatcher;
    }

    @Override
    public Transformer<List<File>, File> getTransform(AttributeContainer from, AttributeContainer to) {
        for (ArtifactTransformRegistrations.ArtifactTransformRegistration transformReg : resolutionStrategy.getTransforms()) {
            Set<Attribute<?>> fromAttributes = transformReg.from.keySet();
            Set<Attribute<?>> toAttributes = transformReg.to.keySet();
            if (attributeMatcher.attributesMatch(from, transformReg.from, fromAttributes)
                && attributeMatcher.attributesMatch(to, transformReg.to, toAttributes)) {
                return transformReg.getTransform();
            }
        }
        return null;
    }

}
