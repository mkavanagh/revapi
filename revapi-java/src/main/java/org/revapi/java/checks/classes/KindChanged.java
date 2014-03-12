/*
 * Copyright 2014 Lukas Krejci
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

package org.revapi.java.checks.classes;

import java.util.Collections;
import java.util.List;

import javax.lang.model.element.TypeElement;

import org.revapi.Report;
import org.revapi.java.checks.AbstractJavaCheck;
import org.revapi.java.checks.Code;

/**
 * @author Lukas Krejci
 * @since 0.1
 */
public final class KindChanged extends AbstractJavaCheck {
    @Override
    protected void doVisitClass(TypeElement oldType, TypeElement newType) {
        if (oldType != null && newType != null && oldType.getKind() != newType.getKind() && isBothAccessible(oldType,
            newType)) {
            pushActive(oldType, newType);
        }
    }

    @Override
    protected List<Report.Difference> doEnd() {
        ActiveElements<TypeElement> types = popIfActive();
        if (types != null) {
            TypeElement o = types.oldElement;
            TypeElement n = types.newElement;

            if (o.getKind() != n.getKind()) {
                Report.Difference p = createDifference(Code.CLASS_KIND_CHANGED,
                    new String[]{kind(o), kind(n)}, o, n);

                return Collections.singletonList(p);
            }
        }

        return null;
    }

    private String kind(TypeElement e) {
        switch (e.getKind()) {
        case CLASS:
            return "class";
        case INTERFACE:
            return "interface";
        case ANNOTATION_TYPE:
            return "@interface";
        case ENUM:
            return "enum";
        default:
            return "unexpected (" + e.getKind() + ")";
        }
    }
}
