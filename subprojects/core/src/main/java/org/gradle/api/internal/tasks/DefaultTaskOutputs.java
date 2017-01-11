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

package org.gradle.api.internal.tasks;

import com.google.common.base.Function;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import groovy.lang.Closure;
import org.gradle.api.Task;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.TaskExecutionHistory;
import org.gradle.api.internal.TaskInternal;
import org.gradle.api.internal.TaskOutputsInternal;
import org.gradle.api.internal.file.CompositeFileCollection;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.api.internal.file.collections.FileCollectionResolveContext;
import org.gradle.api.internal.tasks.CacheableTaskOutputFilePropertySpec.OutputType;
import org.gradle.api.internal.tasks.execution.TaskCachingReasonsListener;
import org.gradle.api.specs.AndSpec;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.TaskOutputFilePropertyBuilder;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.concurrent.Callable;

public class DefaultTaskOutputs implements TaskOutputsInternal {
    private final FileCollection allOutputFiles;
    private AndSpec<TaskInternal> upToDateSpec = AndSpec.empty();
    private Map<String, Spec<? super TaskInternal>> cacheIfSpec = new LinkedHashMap<String, Spec<? super TaskInternal>>();
    private Map<String, Spec<? super TaskInternal>> doNotCacheIfSpec = new LinkedHashMap<String, Spec<? super TaskInternal>>();
    private TaskExecutionHistory history;
    private final List<TaskOutputPropertySpecAndBuilder> filePropertiesInternal = Lists.newArrayList();
    private SortedSet<TaskOutputFilePropertySpec> fileProperties;
    private final FileResolver resolver;
    private final TaskInternal task;
    private final TaskMutator taskMutator;

    public DefaultTaskOutputs(FileResolver resolver, final TaskInternal task, TaskMutator taskMutator) {
        this.resolver = resolver;
        this.task = task;
        this.taskMutator = taskMutator;

        final DefaultTaskDependency buildDependencies = new DefaultTaskDependency();
        buildDependencies.add(task);
        this.allOutputFiles = new TaskOutputUnionFileCollection("task '" + task.getName() + "' output files", buildDependencies);
    }

    @Override
    public Spec<? super TaskInternal> getUpToDateSpec() {
        return upToDateSpec;
    }

    @Override
    public void upToDateWhen(final Closure upToDateClosure) {
        taskMutator.mutate("TaskOutputs.upToDateWhen(Closure)", new Runnable() {
            public void run() {
                upToDateSpec = upToDateSpec.and(upToDateClosure);
            }
        });
    }

    @Override
    public void upToDateWhen(final Spec<? super Task> spec) {
        taskMutator.mutate("TaskOutputs.upToDateWhen(Spec)", new Runnable() {
            public void run() {
                upToDateSpec = upToDateSpec.and(spec);
            }
        });
    }

    @Override
    public boolean isCacheEnabled() {
        return isCacheEnabled(new TaskCachingReasonsListener() {
            @Override
            public void cachingNotEnabled(Task task) {
            }

            @Override
            public void notCacheable(String reason, Task task) {
            }
        });
    }

    @Override
    public boolean isCacheEnabled(TaskCachingReasonsListener taskCachingReasonsListener) {
        Map<String, Spec<? super TaskInternal>> cacheIfSpecs = this.cacheIfSpec;
        if (cacheIfSpecs.isEmpty()) {
            taskCachingReasonsListener.cachingNotEnabled(task);
            return false;
        }
        for (Map.Entry<String, Spec<? super TaskInternal>> messageAndSpec : cacheIfSpecs.entrySet()) {
            String message = messageAndSpec.getKey();
            Spec<? super TaskInternal> spec = messageAndSpec.getValue();
            if (!spec.isSatisfiedBy(task)) {
                taskCachingReasonsListener.notCacheable(message, task);
                return false;
            }
        }
        for (Map.Entry<String, Spec<? super TaskInternal>> messageAndSpec : doNotCacheIfSpec.entrySet()) {
            String message = messageAndSpec.getKey();
            Spec<? super TaskInternal> spec = messageAndSpec.getValue();
            if (spec.isSatisfiedBy(task)) {
                taskCachingReasonsListener.notCacheable(message, task);
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isCacheAllowed() {
        for (TaskPropertySpec spec : getFileProperties()) {
            if (spec instanceof NonCacheableTaskOutputPropertySpec) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void cacheIf(final Spec<? super Task> spec) {
        cacheIf("Unknown reason", spec);
    }

    @Override
    public void cacheIf(final String message, final Spec<? super Task> spec) {
        taskMutator.mutate("TaskOutputs.cacheIf(Spec)", new Runnable() {
            public void run() {
                cacheIfSpec.put(message, spec);
            }
        });
    }

    @Override
    public void doNotCacheIf(final Spec<? super Task> spec) {
        doNotCacheIf("Unknown reason", spec);
    }

    @Override
    public void doNotCacheIf(final String message, final Spec<? super Task> spec) {
        taskMutator.mutate("TaskOutputs.doNotCacheIf(Spec)", new Runnable() {
            public void run() {
                doNotCacheIfSpec.put(message, spec);
            }
        });
    }

    @Override
    public boolean getHasOutput() {
        return hasDeclaredOutputs() || !upToDateSpec.isEmpty();
    }

    @Override
    public boolean hasDeclaredOutputs() {
        return !filePropertiesInternal.isEmpty();
    }

    @Override
    public FileCollection getFiles() {
        return allOutputFiles;
    }

    @Override
    public SortedSet<TaskOutputFilePropertySpec> getFileProperties() {
        if (fileProperties == null) {
            TaskPropertyUtils.ensurePropertiesHaveNames(filePropertiesInternal);
            Iterator<TaskOutputFilePropertySpec> flattenedProperties = Iterators.concat(Iterables.transform(filePropertiesInternal, new Function<TaskPropertySpec, Iterator<? extends TaskOutputFilePropertySpec>>() {
                @Override
                public Iterator<? extends TaskOutputFilePropertySpec> apply(TaskPropertySpec propertySpec) {
                    if (propertySpec instanceof CompositeTaskOutputPropertySpec) {
                        return ((CompositeTaskOutputPropertySpec) propertySpec).resolveToOutputProperties();
                    } else {
                        return Iterators.singletonIterator((TaskOutputFilePropertySpec) propertySpec);
                    }
                }
            }).iterator());
            fileProperties = TaskPropertyUtils.collectFileProperties("output", flattenedProperties);
        }
        return fileProperties;
    }

    @Override
    public TaskOutputFilePropertyBuilder file(final Object path) {
        return taskMutator.mutate("TaskOutputs.file(Object)", new Callable<TaskOutputFilePropertyBuilder>() {
            @Override
            public TaskOutputFilePropertyBuilder call() throws Exception {
                return addSpec(new DefaultCacheableTaskOutputFilePropertySpec(DefaultTaskOutputs.this, task.getName(), resolver, OutputType.FILE, path));
            }
        });
    }

    @Override
    public TaskOutputFilePropertyBuilder dir(final Object path) {
        return taskMutator.mutate("TaskOutputs.dir(Object)", new Callable<TaskOutputFilePropertyBuilder>() {
            @Override
            public TaskOutputFilePropertyBuilder call() throws Exception {
                return addSpec(new DefaultCacheableTaskOutputFilePropertySpec(DefaultTaskOutputs.this, task.getName(), resolver, OutputType.DIRECTORY, path));
            }
        });
    }

    @Override
    public TaskOutputFilePropertyBuilder files(final Object... paths) {
        return taskMutator.mutate("TaskOutputs.files(Object...)", new Callable<TaskOutputFilePropertyBuilder>() {
            @Override
            public TaskOutputFilePropertyBuilder call() throws Exception {
                return addSpec(new CompositeTaskOutputPropertySpec(DefaultTaskOutputs.this, task.getName(), resolver, OutputType.FILE, paths));
            }
        });
    }

    @Override
    public TaskOutputFilePropertyBuilder dirs(final Object... paths) {
        return taskMutator.mutate("TaskOutputs.dirs(Object...)", new Callable<TaskOutputFilePropertyBuilder>() {
            @Override
            public TaskOutputFilePropertyBuilder call() throws Exception {
                return addSpec(new CompositeTaskOutputPropertySpec(DefaultTaskOutputs.this, task.getName(), resolver, OutputType.DIRECTORY, paths));
            }
        });
    }

    private TaskOutputFilePropertyBuilder addSpec(TaskOutputPropertySpecAndBuilder spec) {
        filePropertiesInternal.add(spec);
        return spec;
    }

    @Override
    public FileCollection getPreviousOutputFiles() {
        if (history == null) {
            throw new IllegalStateException("Task history is currently not available for this task.");
        }
        return history.getOutputFiles();
    }

    @Override
    public void setHistory(TaskExecutionHistory history) {
        this.history = history;
    }

    private class TaskOutputUnionFileCollection extends CompositeFileCollection {
        private final String displayName;
        private final DefaultTaskDependency buildDependencies;

        public TaskOutputUnionFileCollection(String displayName, DefaultTaskDependency buildDependencies) {
            this.displayName = displayName;
            this.buildDependencies = buildDependencies;
        }

        @Override
        public String getDisplayName() {
            return displayName;
        }

        @Override
        public void visitContents(FileCollectionResolveContext context) {
            for (TaskFilePropertySpec propertySpec : getFileProperties()) {
                context.add(propertySpec.getPropertyFiles());
            }
        }

        @Override
        public void visitDependencies(TaskDependencyResolveContext context) {
            context.add(buildDependencies);
            super.visitDependencies(context);
        }
    }
}
