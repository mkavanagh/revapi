/*
 * Copyright 2015 Lukas Krejci
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
 * limitations under the License
 */

package org.revapi.java;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.MessageFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.tools.ToolProvider;

import org.revapi.API;
import org.revapi.AnalysisContext;
import org.revapi.Archive;
import org.revapi.Difference;
import org.revapi.DifferenceAnalyzer;
import org.revapi.Element;
import org.revapi.Report;
import org.revapi.Stats;
import org.revapi.java.compilation.CompilationValve;
import org.revapi.java.compilation.ProbingEnvironment;
import org.revapi.java.model.AnnotationElement;
import org.revapi.java.model.FieldElement;
import org.revapi.java.model.MethodElement;
import org.revapi.java.model.MethodParameterElement;
import org.revapi.java.model.TypeElement;
import org.revapi.java.spi.Check;
import org.revapi.java.spi.JavaTypeElement;
import org.revapi.java.spi.UseSite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Lukas Krejci
 * @since 0.1
 */
public final class JavaElementDifferenceAnalyzer implements DifferenceAnalyzer {
    private static final Logger LOG = LoggerFactory.getLogger(JavaElementDifferenceAnalyzer.class);

    //see #forceClearCompilerCache for what these are
    private static final Method CLEAR_COMPILER_CACHE;
    private static final Object SHARED_ZIP_FILE_INDEX_CACHE;
    static {
        Method clearCompilerCache = null;
        Object sharedInstance = null;
        try {
            Class<?> zipFileIndexCacheClass = ToolProvider.getSystemToolClassLoader()
                    .loadClass("com.sun.tools.javac.file.ZipFileIndexCache");

            clearCompilerCache = zipFileIndexCacheClass.getDeclaredMethod("clearCache");
            Method getSharedInstance = zipFileIndexCacheClass.getDeclaredMethod("getSharedInstance");
            sharedInstance = getSharedInstance.invoke(null);
        } catch (Exception e) {
            LOG.warn("Failed to initialize the force-clearing of javac file caches. We will probably leak resources.", e);
        }

        if (clearCompilerCache != null && sharedInstance != null) {
            CLEAR_COMPILER_CACHE = clearCompilerCache;
            SHARED_ZIP_FILE_INDEX_CACHE = sharedInstance;
        } else {
            CLEAR_COMPILER_CACHE = null;
            SHARED_ZIP_FILE_INDEX_CACHE = null;
        }
    }

    private final Iterable<Check> checks;
    private final CompilationValve oldCompilationValve;
    private final CompilationValve newCompilationValve;
    private final AnalysisConfiguration analysisConfiguration;
    private final ResourceBundle messages;
    private final ProbingEnvironment oldEnvironment;
    private final ProbingEnvironment newEnvironment;
    private final Map<Check.Type, List<Check>> checksByInterest;
    private final Deque<Check.Type> checkTypeStack = new ArrayDeque<>();

    // NOTE: this doesn't have to be a stack of lists only because of the fact that annotations
    // are always sorted as last amongst sibling model elements.
    // So, when reported for their parent element, we can be sure that there are no more children
    // coming for given parent.
    private List<Difference> lastAnnotationResults;

    public JavaElementDifferenceAnalyzer(AnalysisContext analysisContext, ProbingEnvironment oldEnvironment,
        CompilationValve oldValve,
        ProbingEnvironment newEnvironment, CompilationValve newValve, Iterable<Check> checks,
        AnalysisConfiguration analysisConfiguration) {

        this.oldCompilationValve = oldValve;
        this.newCompilationValve = newValve;

        this.checks = checks;
        for (Check c : checks) {
            c.initialize(analysisContext);
            c.setOldTypeEnvironment(oldEnvironment);
            c.setNewTypeEnvironment(newEnvironment);
        }

        this.analysisConfiguration = analysisConfiguration;

        messages = ResourceBundle.getBundle("org.revapi.java.messages", analysisContext.getLocale());

        this.oldEnvironment = oldEnvironment;
        this.newEnvironment = newEnvironment;

        this.checksByInterest = new EnumMap<>(Check.Type.class);
        for (Check.Type c : Check.Type.values()) {
            checksByInterest.put(c, new ArrayList<>());
        }

        for (Check c : checks) {
            for (Check.Type t : c.getInterest()) {
                List<Check> cs = checksByInterest.get(t);
                cs.add(c);
            }
        }
    }


    @Override
    public void open() {
        Timing.LOG.debug("Opening difference analyzer.");
    }

    @Override
    public void close() {
        Timing.LOG.debug("About to close difference analyzer.");
        oldCompilationValve.removeCompiledResults();
        newCompilationValve.removeCompiledResults();

        forceClearCompilerCache();

        Timing.LOG.debug("Difference analyzer closed.");
    }

    @Override
    public void beginAnalysis(@Nullable Element oldElement, @Nullable Element newElement) {
        Timing.LOG.trace("Beginning analysis of {} and {}.", oldElement, newElement);

        if (conforms(oldElement, newElement, TypeElement.class)) {
            checkTypeStack.push(Check.Type.CLASS);
            for (Check c : checksByInterest.get(Check.Type.CLASS)) {
                Stats.of(c.getClass().getName()).start();
                c.visitClass(oldElement == null ? null : ((TypeElement) oldElement).getModelElement(),
                    newElement == null ? null : ((TypeElement) newElement).getModelElement());
                Stats.of(c.getClass().getName()).end(oldElement, newElement);
            }
        } else if (conforms(oldElement, newElement, AnnotationElement.class)) {
            // annotation are always terminal elements and they also always sort as last elements amongst siblings, so
            // treat them a bit differently
            if (lastAnnotationResults == null) {
                lastAnnotationResults = new ArrayList<>();
            }
            //DO NOT push the ANNOTATION type to the checkTypeStack. Annotations are handled differently and this would
            //lead to the stack corruption and missed problems!!!
            for (Check c : checksByInterest.get(Check.Type.ANNOTATION)) {
                Stats.of(c.getClass().getName()).start();
                List<Difference> cps = c
                    .visitAnnotation(oldElement == null ? null : ((AnnotationElement) oldElement).getAnnotation(),
                        newElement == null ? null : ((AnnotationElement) newElement).getAnnotation());
                if (cps != null) {
                    lastAnnotationResults.addAll(cps);
                }
                Stats.of(c.getClass().getName()).end(oldElement, newElement);
            }
        } else if (conforms(oldElement, newElement, FieldElement.class)) {
            checkTypeStack.push(Check.Type.FIELD);
            for (Check c : checksByInterest.get(Check.Type.FIELD)) {
                Stats.of(c.getClass().getName()).start();
                c.visitField(oldElement == null ? null : ((FieldElement) oldElement).getModelElement(),
                    newElement == null ? null : ((FieldElement) newElement).getModelElement());
                Stats.of(c.getClass().getName()).end(oldElement, newElement);
            }
        } else if (conforms(oldElement, newElement, MethodElement.class)) {
            checkTypeStack.push(Check.Type.METHOD);
            for (Check c : checksByInterest.get(Check.Type.METHOD)) {
                Stats.of(c.getClass().getName()).start();
                c.visitMethod(oldElement == null ? null : ((MethodElement) oldElement).getModelElement(),
                    newElement == null ? null : ((MethodElement) newElement).getModelElement());
                Stats.of(c.getClass().getName()).end(oldElement, newElement);
            }
        } else if (conforms(oldElement, newElement, MethodParameterElement.class)) {
            checkTypeStack.push(Check.Type.METHOD_PARAMETER);
            for (Check c : checksByInterest.get(Check.Type.METHOD_PARAMETER)) {
                Stats.of(c.getClass().getName()).start();
                c.visitMethodParameter(
                    oldElement == null ? null : ((MethodParameterElement) oldElement).getModelElement(),
                    newElement == null ? null : ((MethodParameterElement) newElement).getModelElement());
                Stats.of(c.getClass().getName()).end(oldElement, newElement);
            }
        }
    }

    @Override
    public Report endAnalysis(@Nullable Element oldElement, @Nullable Element newElement) {
        if (conforms(oldElement, newElement, AnnotationElement.class)) {
            //the annotations are always reported at the parent element
            return new Report(Collections.<Difference>emptyList(), oldElement, newElement);
        }

        List<Difference> differences = new ArrayList<>();
        for (Check c : checksByInterest.get(checkTypeStack.pop())) {
            List<Difference> p = c.visitEnd();
            if (p != null) {
                differences.addAll(p);
            }
        }

        if (lastAnnotationResults != null && !lastAnnotationResults.isEmpty()) {
            differences.addAll(lastAnnotationResults);
            lastAnnotationResults.clear();
        }

        if (!differences.isEmpty()) {
            LOG.trace("Detected following problems: {}", differences);
        }
        Timing.LOG.trace("Ended analysis of {} and {}.", oldElement, newElement);

        ListIterator<Difference> it = differences.listIterator();
        while (it.hasNext()) {
            Difference d = it.next();
            if (analysisConfiguration.getUseReportingCodes().contains(d.code)) {
                StringBuilder newDesc = new StringBuilder(d.description == null ? "" : d.description);
                appendUses(oldElement, oldEnvironment, newDesc);
                appendUses(newElement, newEnvironment, newDesc);

                d = Difference.builder().addAttachments(d.attachments).addClassifications(d.classification)
                    .withCode(d.code).withName(d.name).withDescription(newDesc.toString()).build();
            }
            it.set(d);
        }

        return new Report(differences, oldElement, newElement);
    }

    private <T> boolean conforms(Object a, Object b, Class<T> cls) {
        boolean ca = a == null || cls.isAssignableFrom(a.getClass());
        boolean cb = b == null || cls.isAssignableFrom(b.getClass());

        return ca && cb;
    }

    private void append(StringBuilder bld, TypeAndUseSite typeAndUseSite) {
        String message;
        switch (typeAndUseSite.useSite.getUseType()) {
        case ANNOTATES:
            message = "revapi.java.uses.annotates";
            break;
        case HAS_TYPE:
            message = "revapi.java.uses.hasType";
            break;
        case IS_IMPLEMENTED:
            message = "revapi.java.uses.isImplemented";
            break;
        case IS_INHERITED:
            message = "revapi.java.uses.isInherited";
            break;
        case IS_THROWN:
            message = "revapi.java.uses.isThrown";
            break;
        case PARAMETER_TYPE:
            message = "revapi.java.uses.parameterType";
            break;
        case RETURN_TYPE:
            message = "revapi.java.uses.returnType";
            break;
        case CONTAINS:
            message = "revapi.java.uses.contains";
            break;
        default:
            throw new AssertionError("Invalid use type.");
        }

        message = messages.getString(message);
        message = MessageFormat.format(message, typeAndUseSite.useSite.getSite().getFullHumanReadableString(),
            typeAndUseSite.type.getQualifiedName().toString());

        bld.append(message);
    }

    private void appendUses(Element element, final ProbingEnvironment environment, final StringBuilder bld) {
        if (element instanceof JavaTypeElement) {
            bld.append("\n");
            LOG.trace("Reporting uses of {}", element);

            ((JavaTypeElement) element).visitUseSites(new UseSite.Visitor<Object, Void>() {
                @Nullable
                @Override
                public Object visit(@Nonnull javax.lang.model.element.TypeElement type, @Nonnull UseSite use,
                    @Nullable Void parameter) {
                    if (appendUse(bld, type, use, environment)) {
                        return Boolean.TRUE; //just a non-null values
                    }

                    return null;
                }

                @Nullable
                @Override
                public Object end(javax.lang.model.element.TypeElement type, @Nullable Void parameter) {
                    return null;
                }
            }, null);
        }
    }


    private boolean appendUse(StringBuilder bld, javax.lang.model.element.TypeElement type, UseSite use,
        ProbingEnvironment environment) {

        if (!use.getUseType().isMovingToApi()) {
            return false;
        }

        List<TypeAndUseSite> chain = getExamplePathToApiArchive(type, use, environment);
        Iterator<TypeAndUseSite> chainIt = chain.iterator();

        if (chain.isEmpty()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Could not find example path to API element for type {} starting with use {}",
                    type.getQualifiedName().toString(), use);
            }
            return false;
        }

        TypeAndUseSite last = null;
        if (chainIt.hasNext()) {
            last = chainIt.next();
            append(bld, last);
        }

        while (chainIt.hasNext()) {
            bld.append(" <- ");
            last = chainIt.next();
            append(bld, last);
        }

        String message = MessageFormat.format(messages.getString("revapi.java.uses.partOfApi"),
            last.useSite.getSite().getFullHumanReadableString());

        bld.append(" (").append(message).append(")");

        return true;
    }

    private List<TypeAndUseSite> getExamplePathToApiArchive(javax.lang.model.element.TypeElement type,
        UseSite bottomUse, ProbingEnvironment environment) {

        ArrayList<TypeAndUseSite> ret = new ArrayList<>();

        traverseToApi(type, bottomUse, ret, environment, new HashSet<>());

        return ret;
    }

    private boolean traverseToApi(final javax.lang.model.element.TypeElement type, final UseSite currentUse,
        final List<TypeAndUseSite> path, final ProbingEnvironment environment, final
    Set<javax.lang.model.element.TypeElement> visitedTypes) {

        JavaTypeElement ut = findClassOf(currentUse.getSite());
        javax.lang.model.element.TypeElement useType = ut.getModelElement();

        if (visitedTypes.contains(useType)) {
            return false;
        }

        visitedTypes.add(useType);

        API api = currentUse.getSite().getApi();
        Archive siteArchive = currentUse.getSite().getArchive();

        if (contains(siteArchive, api.getArchives())) {
            //the class is in the primary API
            path.add(0, new TypeAndUseSite(type, currentUse));
            return true;
        } else {
            Boolean ret = ut.visitUseSites(new UseSite.Visitor<Boolean, Void>() {
                @Nullable
                @Override
                public Boolean visit(@Nonnull javax.lang.model.element.TypeElement visitedType, @Nonnull UseSite use,
                    @Nullable Void parameter) {
                    if (traverseToApi(visitedType, use, path, environment, visitedTypes)) {
                        path.add(0, new TypeAndUseSite(type, currentUse));
                        return true;
                    }
                    return null;
                }

                @Nullable
                @Override
                public Boolean end(javax.lang.model.element.TypeElement type, @Nullable Void parameter) {
                    return null;
                }
            }, null);

            return ret == null ? false : ret;
        }
    }

    private JavaTypeElement findClassOf(Element element) {
        while (element != null && !(element instanceof JavaTypeElement)) {
            element = element.getParent();
        }

        return (JavaTypeElement) element;
    }

    private static <T> boolean contains(T value, Iterable<? extends T> values) {
        Iterator<? extends T> it = values.iterator();
        while (it.hasNext()) {
            if (value.equals(it.next())) {
                return true;
            }
        }

        return false;
    }

    //Javac's standard file manager is leaking resources across compilation tasks because it doesn't clear a shared
    //"zip file index" cache, when it is close()'d. We try to clear it by force.
    private static void forceClearCompilerCache() {
        if (CLEAR_COMPILER_CACHE != null && SHARED_ZIP_FILE_INDEX_CACHE != null) {
            try {
                CLEAR_COMPILER_CACHE.invoke(SHARED_ZIP_FILE_INDEX_CACHE);
            } catch (IllegalAccessException | InvocationTargetException e) {
                LOG.warn("Failed to force-clear compiler caches, even though it should have been possible." +
                                "This will probably leak memory", e);
            }
        }
    }

    private static class TypeAndUseSite {
        final javax.lang.model.element.TypeElement type;
        final UseSite useSite;

        public TypeAndUseSite(javax.lang.model.element.TypeElement type, UseSite useSite) {
            this.type = type;
            this.useSite = useSite;
        }
    }
}
