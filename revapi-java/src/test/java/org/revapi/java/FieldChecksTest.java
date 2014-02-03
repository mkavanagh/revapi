/*
 * Copyright 2014 Lukas Krejci
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package org.revapi.java;

import org.junit.Assert;
import org.junit.Test;

import org.revapi.java.checks.Code;

/**
 * @author Lukas Krejci
 * @since 0.1
 */
public class FieldChecksTest extends AbstractJavaElementAnalyzerTest {

    @Test
    public void testFieldAdded() throws Exception {
        ProblemOccurrenceReporter reporter = new ProblemOccurrenceReporter();
        runAnalysis(reporter, "v1/fields/Added.java", "v2/fields/Added.java");

        Assert.assertEquals(2, (int) reporter.getProblemCounters().get(Code.FIELD_ADDED_IN_NON_FINAL_CLASS.code()));
    }

    @Test
    public void testFieldRemoved() throws Exception {
        ProblemOccurrenceReporter reporter = new ProblemOccurrenceReporter();
        runAnalysis(reporter, "v2/fields/Added.java", "v1/fields/Added.java");

        Assert.assertEquals(2, (int) reporter.getProblemCounters().get(Code.FIELD_REMOVED.code()));
    }

    @Test
    public void testConstantValueChanged() throws Exception {
        ProblemOccurrenceReporter reporter = new ProblemOccurrenceReporter();
        runAnalysis(reporter, "v1/fields/Constants.java", "v2/fields/Constants.java");

        Assert.assertEquals(1, (int) reporter.getProblemCounters().get(Code.FIELD_CONSTANT_VALUE_CHANGED.code()));
    }

    @Test
    public void testBecameConstant() throws Exception {
        ProblemOccurrenceReporter reporter = new ProblemOccurrenceReporter();
        runAnalysis(reporter, "v1/fields/Constants.java", "v2/fields/Constants.java");

        Assert.assertEquals(1, (int) reporter.getProblemCounters().get(Code.FIELD_NOW_CONSTANT.code()));
    }

    @Test
    public void testFieldWithConstantValueRemoved() throws Exception {
        ProblemOccurrenceReporter reporter = new ProblemOccurrenceReporter();
        runAnalysis(reporter, "v1/fields/Constants.java", "v2/fields/Constants.java");

        Assert.assertEquals(1, (int) reporter.getProblemCounters().get(Code.FIELD_CONSTANT_REMOVED.code()));
    }
}
