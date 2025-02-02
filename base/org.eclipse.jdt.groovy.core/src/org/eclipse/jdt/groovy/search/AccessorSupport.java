/*
 * Copyright 2009-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.eclipse.jdt.groovy.search;

import static org.codehaus.groovy.runtime.DefaultGroovyMethods.asBoolean;
import static org.eclipse.jdt.groovy.core.util.GroovyUtils.implementsTrait;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;

import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.runtime.MetaClassHelper;

/**
 * Kind of accessor a method name may be and then does further processing on a method node if the name matches.
 */
public enum AccessorSupport {
    ISSER("is"), GETTER("get"), SETTER("set"), NONE("");

    AccessorSupport(String prefix) {
        this.prefix = prefix;
    }

    private final String prefix;

    //

    public boolean isAccessor() {
        return (this != NONE);
    }

    public boolean isAccessorKind(MethodNode node, boolean isCategory) {
        Parameter[] parameters = node.getParameters();
        switch (this) {
        case GETTER:
            return (parameters == null || parameters.length == (!isCategory ? 0 : 1)) && !node.isVoidMethod();
        case SETTER:
            return (parameters != null && parameters.length == (!isCategory ? 1 : 2)) && (!isCategory || !GenericsMapper.isVargs(parameters));
        case ISSER:
            return (parameters == null || parameters.length == (!isCategory ? 0 : 1)) && node.getReturnType().equals(ClassHelper.boolean_TYPE);
        default:
            return false;
        }
    }

    public String createAccessorName(String name) {
        if (isAccessor() && !name.isEmpty() && !name.startsWith(GETTER.prefix) && !name.startsWith(SETTER.prefix)) {
            return this.prefix + Character.toUpperCase(name.charAt(0)) + (name.length() > 1 ? name.substring(1) : "");
        }
        return null;
    }

    public static AccessorSupport create(String methodName, boolean isCategory) {
        AccessorSupport accessor = AccessorSupport.NONE;

        for (AccessorSupport kind : AccessorSupport.values()) {
            if (kind.isAccessor() &&
                    methodName.startsWith(kind.prefix) &&
                    methodName.length() > kind.prefix.length() &&
                    Character.isUpperCase(methodName.charAt(kind.prefix.length()))) {
                accessor = kind;
                break;
            }
        }

        return accessor;
    }

    public static AccessorSupport findAccessorKind(MethodNode node, boolean isCategory) {
        AccessorSupport accessor = create(node.getName(), isCategory);
        return accessor.isAccessorKind(node, isCategory) ? accessor : NONE;
    }

    public static MethodNode findAccessorMethodForPropertyName(String name, ClassNode declaringType, boolean isCategory) {
        return findAccessorMethodForPropertyName(name, declaringType, isCategory, ISSER, GETTER, SETTER);
    }

    public static MethodNode findAccessorMethodForPropertyName(String name, ClassNode declaringType, boolean isCategory, AccessorSupport... kinds) {
        return findAccessorMethodsForPropertyName(name, declaringType, isCategory, kinds).findFirst().orElse(null);
    }

    public static Stream<MethodNode> findAccessorMethodsForPropertyName(String name, ClassNode declaringType, boolean isCategory, AccessorSupport... kinds) {
        Stream<MethodNode> methods = Stream.empty();

        if (name != null && name.length() > 0 && kinds != null && kinds.length > 0) {
            String suffix = MetaClassHelper.capitalize(name);
            for (AccessorSupport kind : kinds) {
                if (kind == NONE) continue;
                String methodName = kind.prefix + suffix;
                methods = Stream.concat(methods, findAccessorMethodsForMethodName(methodName, declaringType, isCategory, kind));

                // abstract types do not track undeclared abstract methods; concrete types do not track interface default methods
                if (declaringType.isAbstract() || asBoolean(declaringType.getInterfaces()) || implementsTrait(declaringType)) {
                    Set<ClassNode> faces = new LinkedHashSet<>();
                    VariableScope.findAllInterfaces(declaringType, faces, false);
                    faces.remove(declaringType); // checked already
                    for (ClassNode face : faces) {
                        methods = Stream.concat(methods, findAccessorMethodsForMethodName(methodName, face, isCategory, kind));
                    }
                    // one implicit accessor exists in Object
                    if (!isCategory && kind == GETTER && "getClass".equals(methodName)) {
                        methods = Stream.concat(methods, Stream.of(ClassHelper.OBJECT_TYPE.getMethod("getClass", Parameter.EMPTY_ARRAY)));
                    }
                }
            }
        }

        return methods;
    }

    private static Stream<MethodNode> findAccessorMethodsForMethodName(String name, ClassNode declaringType, boolean isCategory, AccessorSupport kind) {
        return SimpleTypeLookup.getMethods(name, declaringType).stream().filter(meth -> kind == findAccessorKind(meth, isCategory));
    }

    /**
     * @return {@code true} if the methodNode looks like a getter method for a property:
     *         method starting <tt>get<i>Something</i></tt> with a non-void return type and taking no parameters
     */
    public static boolean isGetter(MethodNode node) {
        return !node.isVoidMethod() && node.getParameters().length == 0 &&
            ((node.getName().startsWith("get") && node.getName().length() > 3) ||
                (node.getName().startsWith("is") && node.getName().length() > 2));
    }

    public static boolean isSetter(MethodNode node) {
        return node.getParameters().length == 1 &&
            (node.getName().startsWith("set") && node.getName().length() > 3);
    }
}
