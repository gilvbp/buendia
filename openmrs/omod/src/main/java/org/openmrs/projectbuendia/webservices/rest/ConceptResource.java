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

import org.openmrs.Concept;
import org.openmrs.ConceptAnswer;
import org.openmrs.Field;
import org.openmrs.Form;
import org.openmrs.FormField;
import org.openmrs.api.ConceptService;
import org.openmrs.api.FormService;
import org.openmrs.api.context.Context;
import org.openmrs.hl7.HL7Constants;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.RequestContext;
import org.openmrs.module.webservices.rest.web.annotation.Resource;
import org.openmrs.module.webservices.rest.web.representation.Representation;
import org.openmrs.projectbuendia.ClientConceptNamer;
import org.openmrs.projectbuendia.Utils;
import org.projectbuendia.openmrs.webservices.rest.RestController;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.openmrs.projectbuendia.Utils.isBlank;

/**
 * REST collection of all the concepts that are present in at least one of the
 * charts returned by {@link ChartResource}.
 * @see AbstractReadOnlyResource
 */
@Resource(name = RestController.REST_VERSION_1_AND_NAMESPACE + "/concepts",
    supportedClass = Concept.class, supportedOpenmrsVersions = "1.10.*,1.11.*")
public class ConceptResource extends AbstractReadOnlyResource<Concept> {
    /** A map from HL7 type abbreviations to short names used in JSON output. */
    private static final Map<String, String> HL7_TYPE_NAMES = new HashMap<>();

    static {
        HL7_TYPE_NAMES.put(HL7Constants.HL7_BOOLEAN, "coded");
        HL7_TYPE_NAMES.put(HL7Constants.HL7_CODED, "coded");
        HL7_TYPE_NAMES.put(HL7Constants.HL7_CODED_WITH_EXCEPTIONS, "coded");
        HL7_TYPE_NAMES.put(HL7Constants.HL7_TEXT, "text");
        HL7_TYPE_NAMES.put(HL7Constants.HL7_NUMERIC, "numeric");
        HL7_TYPE_NAMES.put(HL7Constants.HL7_DATE, "date");
        HL7_TYPE_NAMES.put(HL7Constants.HL7_DATETIME, "datetime");
        HL7_TYPE_NAMES.put("ZZ", "none");
    }

    private final FormService formService;
    private final ConceptService conceptService;
    private final ClientConceptNamer namer;

    public ConceptResource() {
        super("concept", Representation.DEFAULT);
        formService = Context.getFormService();
        conceptService = Context.getConceptService();
        namer = new ClientConceptNamer(Context.getLocale());
    }

    /**
     * Retrieves a single concept with the given UUID.
     * @param context the request context; specify the URL query parameter
     *                "locales=[comma-separated list]" to get localized concept names for
     *                specific locales; otherwise, all available locales will be returned.
     * @see AbstractReadOnlyResource#retrieve(String, RequestContext)
     */
    @Override
    protected Concept retrieveImpl(String uuid, RequestContext context, long snapshotTime) {
        Concept concept = conceptService.getConceptByUuid(uuid);
        return (concept == null || concept.isRetired()) ? null : concept;
    }

    /**
     * Returns all concepts present in at least one of the charts returned by
     * {@link ChartResource} (there is no support for searching or filtering).
     * @param context the request context; specify the URL query parameter
     *                "locales=[comma-separated list]" to get localized concept names for
     *                specific locales; otherwise, all available locales will be returned.
     * @see AbstractReadOnlyResource#search(RequestContext)
     */
    @Override protected Iterable<Concept> searchImpl(RequestContext context, long snapshotTime) {
        // Retrieves all the concepts that the client needs to know about
        // (the concepts within all the charts served by ChartResource).
        Set<Concept> concepts = new HashSet<>();
        for (Form chart : ChartResource.getCharts(formService)) {
            for (FormField formField : chart.getFormFields()) {
                Field field = formField.getField();
                Concept concept = field.getConcept();
                if (concept != null && !concept.isRetired()) {
                    concepts.add(concept);
                    for (ConceptAnswer answer : concept.getAnswers(false)) {
                        concepts.add(answer.getAnswerConcept());
                    }
                }
                // Fetch and add additional concepts that might have been stored in the field
                // description.
                for (Concept extra : getConceptsFromFieldDescription(field.getDescription())) {
                    if (extra != null && !extra.isRetired()) {
                        concepts.add(extra);
                    }
                }
            }
        }
        return concepts;
    }

    /**
     * Buendia supports multiple concepts per field, but OpenMRS does not. To handle this, Buendia
     * packs additional concept IDs into the JSON field description that it generates in the
     * {@code profile_apply} script.
     * <p>
     * This method extracts those concept IDs and converts them into {@link Concept}s.
     *
     * @param fieldDescription The output of {@link Field#getDescription()}.
     * @return a list of referenced concepts, or an empty list if the field description doesn't
     * match the expected input format.
     */
    private static List<Concept> getConceptsFromFieldDescription(
            @Nullable String fieldDescription) {
        if (fieldDescription == null) {
            return Collections.emptyList();
        }
        List<Concept> returnValue = new ArrayList<>();
        try {
            SimpleObject extendedData = SimpleObject.parseJson(fieldDescription);
            Object object = extendedData.get("concepts");
            if (! (object instanceof List)) {
                return Collections.emptyList();
            }

            List list = (List) object;
            for (Object obj : list) {
                if (obj == null) {
                    continue;
                }
                // We're ok with a ClassCastException here if this isn't an Integer.
                Integer conceptId = (Integer) obj;
                Concept concept = Context.getConceptService().getConcept(conceptId);
                if (concept == null || concept.isRetired()) {
                    continue;
                }
                returnValue.add(concept);
            }
        } catch (IOException ignore) {
        }
        return returnValue;
    }

    /**
     * Adds the following fields to the {@link SimpleObject}:
     * <ul>
     * <li>"xform_id": the ID used to identify the concept in an xform
     * (not the concept's UUID)
     * <li>"type": one of the following type names, indicating the concept
     * datatype as defined by OpenMRS:
     * <ul>
     * <li>coded
     * <li>text
     * <li>numeric
     * <li>datetime
     * <li>date
     * <li>none
     * </ul>
     * <li>names: a {@link Map} of locales to concept names.  If locales
     * are specified in the "locales" URL query parameter, only those
     * locales will appear; otherwise all allowed locales will appear.
     * </ul>
     * @param context the request context; specify the URL query parameter
     *                "locales=[comma-separated list]" to get localized concept names for
     *                specific locales; otherwise, all available locales will be returned.
     */
    @Override protected void populateJsonProperties(
        Concept concept, RequestContext context, SimpleObject json, long snapshotTime) {
        // TODO: Cache this in the request context?
        List<Locale> locales = getLocalesForRequest(context);
        String jsonType = HL7_TYPE_NAMES.get(concept.getDatatype().getHl7Abbreviation());
        if (jsonType == null) {
            throw new ConfigurationException("Concept %s has unmapped HL7 data type %s",
                concept.getName().getName(), concept.getDatatype().getHl7Abbreviation());
        }
        json.put("xform_id", concept.getId());
        json.put("type", jsonType);
        Map<String, String> names = new HashMap<>();
        for (Locale locale : locales) {
            names.put(locale.toString(), namer.getClientName(concept));
        }
        json.put("names", names);
    }

    private List<Locale> getLocalesForRequest(RequestContext context) {
        // TODO: Make this cheap to call multiple times for a single request.
        String localeIds = context.getRequest().getParameter("locales");
        if (isBlank(localeIds)) {
            return Context.getAdministrationService().getAllowedLocales();
        }
        List<Locale> locales = new ArrayList<>();
        for (String localeId : localeIds.split(",")) {
            locales.add(Locale.forLanguageTag(localeId.trim()));
        }
        return locales;
    }
}
