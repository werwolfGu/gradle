/*
 * Copyright 2009 the original author or authors.
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
package org.gradle.api.internal;

import com.google.common.collect.Iterators;
import com.google.common.collect.Sets;
import org.apache.commons.collections.collection.CompositeCollection;
import org.gradle.api.Action;
import org.gradle.api.DomainObjectCollection;
import org.gradle.api.specs.Spec;
import org.gradle.internal.Actions;
import org.gradle.internal.Cast;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.gradle.api.internal.WithEstimatedSize.Estimates.estimateSizeOf;

/**
 * A domain object collection that presents a combined view of one or more collections.
 *
 * @param <T> The type of domain objects in the component collections of this collection.
 */
public class CompositeDomainObjectSet<T> extends DelegatingDomainObjectSet<T> implements WithEstimatedSize {

    private final Spec<T> uniqueSpec = new ItemIsUniqueInCompositeSpec();
    private final Spec<T> notInSpec = new ItemNotInCompositeSpec();
    private final DefaultDomainObjectSet<T> backingSet;

    public static <T> CompositeDomainObjectSet<T> create(Class<T> type, DomainObjectCollection<? extends T>... collections) {
        //noinspection unchecked
        DefaultDomainObjectSet<T> backingSet = new DefaultDomainObjectSet<T>(type, new CompositeCollection());
        CompositeDomainObjectSet<T> out = new CompositeDomainObjectSet<T>(backingSet);
        for (DomainObjectCollection<? extends T> c : collections) {
            out.addCollection(c);
        }
        return out;
    }

    CompositeDomainObjectSet(DefaultDomainObjectSet<T> backingSet) {
        super(backingSet);
        this.backingSet = backingSet; //TODO SF try avoiding keeping this state here
    }

    public class ItemIsUniqueInCompositeSpec implements Spec<T> {
        public boolean isSatisfiedBy(T element) {
            int matches = 0;
            for (Object collection : getStore().getCollections()) {
                if (((Collection) collection).contains(element)) {
                    if (++matches > 1) {
                        return false;
                    }
                }
            }

            return true;
        }
    }

    public class ItemNotInCompositeSpec implements Spec<T> {
        public boolean isSatisfiedBy(T element) {
            return !getStore().contains(element);
        }
    }

    @SuppressWarnings("unchecked")
    protected CompositeCollection getStore() {
        return (CompositeCollection) this.backingSet.getStore();
    }

    public Action<? super T> whenObjectAdded(Action<? super T> action) {
        return super.whenObjectAdded(Actions.filter(action, uniqueSpec));
    }

    public Action<? super T> whenObjectRemoved(Action<? super T> action) {
        return super.whenObjectRemoved(Actions.filter(action, notInSpec));
    }

    public void addCollection(DomainObjectCollection<? extends T> collection) {
        if (!getStore().getCollections().contains(collection)) {
            getStore().addComposited(collection);
            collection.all(backingSet.getEventRegister().getAddAction());
            collection.whenObjectRemoved(backingSet.getEventRegister().getRemoveAction());
        }
    }

    public void removeCollection(DomainObjectCollection<? extends T> collection) {
        getStore().removeComposited(collection);
        Action<? super T> action = this.backingSet.getEventRegister().getRemoveAction();
        for (T item : collection) {
            action.execute(item);
        }
    }

    @SuppressWarnings({"NullableProblems", "unchecked"})
    @Override
    public Iterator<T> iterator() {
        CompositeCollection store = getStore();
        if (store.isEmpty()) {
            return Iterators.emptyIterator();
        }
        Collection<Collection<T>> collections = getStore().getCollections();
        Iterator<T> iterator;
        if (collections instanceof List && collections.size()==1) {
            // shortcut Apache Commons iterator() which creates a chaining iterator even if there's
            // only one underlying collection
            iterator = (((List<Collection<T>>) collections).get(0)).iterator();
        } else {
            iterator = getStore().iterator();
        }
        return SetIterator.wrap(iterator);
    }

    @SuppressWarnings("unchecked")
    /**
     * This method is expensive. Avoid calling it if possible. If all you need is a rough
     * estimate, call {@link #estimatedSize()} instead.
     */
    public int size() {
        CompositeCollection store = getStore();
        if (store.isEmpty()) {
            return 0;
        }
        Set<T> tmp = Sets.newHashSetWithExpectedSize(estimatedSize());
        tmp.addAll(store);
        return tmp.size();
    }

    @Override
    public int estimatedSize() {
        CompositeCollection store = getStore();
        if (store.isEmpty()) {
            return 0;
        }
        Collection<Collection<T>> collections = Cast.uncheckedCast(getStore().getCollections());
        int size = 0;
        for (Collection<T> collection : collections) {
            size += estimateSizeOf(collection);
        }
        return size;
    }

    public void all(Action<? super T> action) {
        //calling overloaded method with extra behavior:
        whenObjectAdded(action);

        for (T t : this) {
            action.execute(t);
        }
    }

}
