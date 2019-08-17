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

import org.apache.commons.lang3.StringUtils;
import org.openmrs.Patient;
import org.openmrs.Person;
import org.openmrs.Provider;
import org.openmrs.api.ProviderService;
import org.openmrs.api.UserService;
import org.openmrs.api.context.Context;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.web.RequestContext;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.annotation.Resource;
import org.openmrs.module.webservices.rest.web.representation.Representation;
import org.openmrs.module.webservices.rest.web.resource.api.Creatable;
import org.openmrs.module.webservices.rest.web.resource.api.Listable;
import org.openmrs.module.webservices.rest.web.resource.api.Retrievable;
import org.openmrs.module.webservices.rest.web.resource.api.Searchable;
import org.openmrs.module.webservices.rest.web.response.ObjectNotFoundException;
import org.openmrs.module.webservices.rest.web.response.ResponseException;
import org.projectbuendia.openmrs.webservices.rest.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.openmrs.projectbuendia.Utils.getRequiredString;

/**
 * Resource for users (note that users are stored as Providers, Persons, and Users, but only
 * Providers will be returned by List calls).
 * <p/>
 * <p>Expected behavior:
 * <ul>
 * <li>GET /user returns all users ({@link #getAll(RequestContext)})
 * <li>GET /user/[UUID] returns a single user ({@link #retrieve(String, RequestContext)})
 * <li>GET /user?q=[QUERY] returns users whose full name contains the query string
 * ({@link #search(RequestContext)})
 * <li>POST /user creates a new user ({@link #create(SimpleObject, RequestContext)})
 * </ul>
 * <p/>
 * <p>All GET operations return User resources in the following JSON form:
 * <pre>
 * {
 *   user_id: "5a382-9", // UUID for the user
 *   full_name: "John Smith", // constructed from given and family name
 *   given_name: "John",
 *   family_name: "Smith"
 * }
 * </pre>
 * <p/>
 * <p>User creation expects a slightly different format:
 * <pre>
 * {
 *   user_name: "jsmith", // user id which can be used to log into OpenMRS
 *   password: "Password123", // must have &gt; 8 characters, at least 1 digit and 1 uppercase
 *   given_name: "John",
 *   family_name: "Smith"
 * }
 * </pre>
 * <p/>
 * <p>If an error occurs, the response will contain the following:
 * <pre>
 * {
 *   "error": {
 *     "message": "[error message]",
 *     "code": "[breakpoint]",
 *     "detail": "[stack trace]"
 *   }
 * }
 * </pre>
 */
@Resource(name = RestController.REST_VERSION_1_AND_NAMESPACE + "/xusers",
    supportedClass = Provider.class, supportedOpenmrsVersions = "1.10.*,1.11.*")
public class UserResource implements Listable, Searchable, Retrievable, Creatable {
    // JSON property names
    private static final String USER_ID = "user_id";
    private static final String USER_NAME = "user_name";
    private static final String FULL_NAME = "full_name";  // Ignored on create.
    private static final String FAMILY_NAME = "family_name";
    private static final String GIVEN_NAME = "given_name";

    // Defaults for guest provider
    private static final String PROVIDER_GUEST_UUID = "buendia_provider_guest";
    private static final String GUEST_NAME = "Guest User";

    private static final Object guestAddLock = new Object();

    static final RequestLogger logger = RequestLogger.LOGGER;

    private final ProviderService providerService;
    private final UserService userService;

    public UserResource() {
        providerService = Context.getProviderService();
        userService = Context.getUserService();
    }

    /** Returns all Providers. */
    @Override public SimpleObject getAll(RequestContext context) throws ResponseException {
        try {
            logger.request(context, this, "getAll");
            SimpleObject result = getAllInner();
            logger.reply(context, this, "getAll", result);
            return result;
        } catch (Exception e) {
            logger.error(context, this, "getAll", e);
            throw e;
        }
    }

    /** Returns all Providers. */
    private SimpleObject getAllInner() throws ResponseException {
        ensureGuestProviderExists();
        return getSimpleObjectWithResults(providerService.getAllProviders());
    }

    /** Creates a Provider named "Guest User" if one doesn't already exist. */
    private synchronized void ensureGuestProviderExists() {
        Provider guest = providerService.getProviderByUuid(PROVIDER_GUEST_UUID);
        if (guest == null) {
            guest = new Provider();
            guest.setCreator(DbUtils.getAuthenticatedUser());
            guest.setUuid(PROVIDER_GUEST_UUID);
            guest.setName(GUEST_NAME);
            providerService.saveProvider(guest);
        }
    }

    /**
     * Converts a list of Providers into a SimpleObject in the form
     * {"results": [...]} with an array of SimpleObjects, one for each Provider.
     */
    private SimpleObject getSimpleObjectWithResults(List<Provider> providers) {
        List<SimpleObject> jsonResults = new ArrayList<>();
        for (Provider provider : providers) {
            if (provider == null || provider.isRetired()) continue;
            jsonResults.add(providerToJson(provider));
        }
        SimpleObject list = new SimpleObject();
        list.add("results", jsonResults);
        return list;
    }

    /** Constructs and saves a new Provider based on the given JSON object. */
    private Provider addNewProvider(SimpleObject obj) {
        String givenName = getRequiredString(obj, GIVEN_NAME);
        String familyName = getRequiredString(obj, FAMILY_NAME);
        String name = (givenName + " " + familyName).trim();
        if (name.isEmpty()) {
            throw new InvalidObjectDataException("Both name fields are empty");
        }

        Provider provider = new Provider();
        provider.setCreator(DbUtils.getAuthenticatedUser());
        provider.setName(name);
        providerService.saveProvider(provider);
        return provider;
    }

    /** Builds a SimpleObject describing the given Provider. */
    private SimpleObject providerToJson(Provider provider) {
        SimpleObject jsonForm = new SimpleObject();
        if (provider != null) {
            jsonForm.add(USER_ID, provider.getUuid());
            jsonForm.add(FULL_NAME, provider.getName());
            Person person = provider.getPerson();
            if (person != null) {
                jsonForm.add(GIVEN_NAME, person.getGivenName());
                jsonForm.add(FAMILY_NAME, person.getFamilyName());
            }
        }
        return jsonForm;
    }

    /** Throws an exception if the given SimpleObject is missing any required fields. */
    private void checkRequiredFields(SimpleObject simpleObject, String[] requiredFields) {
        List<String> missingFields = new ArrayList<>();
        for (String requiredField : requiredFields) {
            if (!simpleObject.containsKey(requiredField)) {
                missingFields.add(requiredField);
            }
        }

        if (!missingFields.isEmpty()) {
            throw new InvalidObjectDataException(
                "JSON object lacks required fields: " + StringUtils.join(missingFields, ","));
        }
    }

    /** Adds a new Provider. */
    @Override
    public Object create(SimpleObject obj, RequestContext context) throws ResponseException {
        try {
            logger.request(context, this, "create", obj);
            Object result = createInner(obj);
            logger.reply(context, this, "create", result);
            return result;
        } catch (Exception e) {
            logger.error(context, this, "create", e);
            throw e;
        }
    }

    private Object createInner(SimpleObject simpleObject) throws ResponseException {
        return providerToJson(addNewProvider(simpleObject));
    }

    @Override public String getUri(Object instance) {
        Patient patient = (Patient) instance;
        Resource res = getClass().getAnnotation(Resource.class);
        return RestConstants.URI_PREFIX + res.name() + "/" + patient.getUuid();
    }

    /** Returns a specific Provider. */
    @Override public Object retrieve(String uuid, RequestContext context) throws ResponseException {
        try {
            logger.request(context, this, "retrieve");
            Object result = retrieveInner(uuid);
            logger.reply(context, this, "retrieve", result);
            return result;
        } catch (Exception e) {
            logger.error(context, this, "retrieve", e);
            throw e;
        }
    }

    private Object retrieveInner(String uuid) throws ResponseException {
        Provider provider = providerService.getProviderByUuid(uuid);
        if (provider == null) {
            throw new ObjectNotFoundException();
        }
        return providerToJson(provider);
    }

    @Override public List<Representation> getAvailableRepresentations() {
        return Arrays.asList(Representation.DEFAULT);
    }

    /** Searches for Providers whose names contain the 'q' parameter. */
    @Override public SimpleObject search(RequestContext context) throws ResponseException {
        try {
            logger.request(context, this, "search");
            SimpleObject result = searchInner(context);
            logger.reply(context, this, "search", result);
            return result;
        } catch (Exception e) {
            logger.error(context, this, "search", e);
            throw e;
        }
    }

    /** Searches for Providers whose names contain the 'q' parameter. */
    private SimpleObject searchInner(RequestContext requestContext) throws ResponseException {
        ensureGuestProviderExists();

        // Retrieve all patients and filter the list based on the query.
        String query = requestContext.getParameter("q");
        List<Provider> filteredProviders = new ArrayList<>();
        for (Provider provider : providerService.getAllProviders(false)) {
            if (StringUtils.containsIgnoreCase(provider.getName(), query)) {
                filteredProviders.add(provider);
            }
        }
        return getSimpleObjectWithResults(filteredProviders);
    }
}
