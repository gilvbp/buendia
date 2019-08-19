// Copyright 2015 The Project Buendia Authors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not
// use this file except in compliance with the License.  You may obtain a copy
// of the License at: http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distrib-
// uted under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied.  See the License for
// specific language governing permissions and limitations under the License.

package org.openmrs.projectbuendia.webservices.rest;

import org.junit.Test;

import static org.openmrs.projectbuendia.webservices.rest.XmlTestUtils.assertXmlEqual;
import static org.openmrs.projectbuendia.webservices.rest.XmlTestUtils.getStringResource;

public class XformResourceTest {
    @Test public void convertToOdkCollect() throws Exception {
        String input = getStringResource(getClass(), "sample-original-form1.xml");
        String expected = getStringResource(getClass(), "expected-result-form1.xml");
        String actual = XformResource.convertToOdkCollect(input, "Form title");
        assertXmlEqual(expected, actual);
    }

    @Test public void removeRelationshipNodes() throws Exception {
        String input = getStringResource(getClass(), "relationships-original-form1.xml");
        String expected = getStringResource(getClass(), "relationships-result-form1.xml");
        String actual = XformResource.removeRelationshipNodes(input);
        assertXmlEqual(expected, actual);
    }
}
