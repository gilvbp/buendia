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

package org.projectbuendia.openmrs.api.db;

import org.openmrs.Obs;
import org.openmrs.Order;
import org.openmrs.Patient;
import org.projectbuendia.openmrs.api.ProjectBuendiaService;
import org.projectbuendia.openmrs.api.Bookmark;

import javax.annotation.Nullable;

/** Database methods for {@link ProjectBuendiaService}. */
public interface ProjectBuendiaDAO {

    SyncPage<Obs> getObservationsModifiedAfter(
        @Nullable Bookmark bookmark, boolean includeVoided, int maxResults);

    SyncPage<Patient> getPatientsModifiedAfter(
        @Nullable Bookmark bookmark, boolean includeVoided, int maxResults);

    SyncPage<Order> getOrdersModifiedAtOrAfter(
        @Nullable Bookmark bookmark, boolean includeVoided, int maxResults,
        @Nullable Order.Action[] allowedOrderTypes);
}