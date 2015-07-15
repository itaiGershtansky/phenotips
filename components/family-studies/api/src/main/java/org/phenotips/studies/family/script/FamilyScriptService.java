/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/
 */
package org.phenotips.studies.family.script;

import org.phenotips.data.Patient;
import org.phenotips.data.PatientRepository;
import org.phenotips.studies.family.Family;
import org.phenotips.studies.family.FamilyRepository;
import org.phenotips.studies.family.FamilyUtils;
import org.phenotips.studies.family.Processing;
import org.phenotips.studies.family.Validation;
import org.phenotips.studies.family.internal.PedigreeUtils;
import org.phenotips.studies.family.internal.export.XWikiFamilyExport;

import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.script.service.ScriptService;

import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;

import com.xpn.xwiki.XWikiException;

import net.sf.json.JSON;
import net.sf.json.JSONObject;

/**
 * Script service for working with families.
 *
 * @version $Id$
 * @since 1.2RC1
 */
@Component
@Singleton
@Named("families")
public class FamilyScriptService implements ScriptService
{
    @Inject
    private Logger logger;

    @Inject
    private FamilyRepository familyRepository;

    @Inject
    private PatientRepository patientRepository;

    @Inject
    private FamilyUtils utils;

    @Inject
    private Processing processing;

    @Inject
    private Validation validation;

    @Inject
    private XWikiFamilyExport familyExport;

    /**
     * Either creates a new family, or gets the existing one if a patient belongs to a family. TODO - change Name to
     * getFamily
     *
     * @param patientId id of the patient to use when searching for or creating a new family
     * @return reference to the family document. Can be {@link null}
     */
    public DocumentReference createFamily(String patientId)
    {
        Patient patient = this.patientRepository.getPatientById(patientId);
        if (patient == null) {
            this.logger.error("Could not find patient with id [{}]", patientId);
            return null;
        }

        Family xwikiFamily = this.familyRepository.getFamilyForPatient(patient);
        if (xwikiFamily == null) {
            this.logger.debug("No family for patient [{}]. Creating new.", patientId);
            xwikiFamily = this.familyRepository.createFamily();
            xwikiFamily.addMember(patient);
        }

        return xwikiFamily.getDocumentReference();
    }

    /**
     * Creates an empty family.
     *
     * @return DocumentReference to the newly created family
     */
    public DocumentReference createFamily()
    {
        Family family = this.familyRepository.createFamily();
        return family.getDocumentReference();
    }

    /**
     * Returns a reference to a patient's family, or null if doesn't exist. TODO - change call to pass patientId and not
     * XWikiDocument ($doc.getDocument() -> $doc.getDocument().getName()). Also change name to getFamilyForPatientId
     *
     * @param patientId id of the patient
     * @return DocumentReference to the family, or null if doesn't exist
     */
    public DocumentReference getPatientsFamily(String patientId)
    {
        Patient patient = this.patientRepository.getPatientById(patientId);
        Family family = this.familyRepository.getFamilyForPatient(patient);
        if (family == null) {
            return null;
        }
        return family.getDocumentReference();
    }

    /**
     * Gets a family id or patient id. If the id is a patient id, finds the patient's family and returns a family
     * information JSON. If the id is a family id, returns the family information.
     *
     * @param id family id or the id of a patient who belongs to a family
     * @return JSON data structure with information about the family
     */
    public JSON getFamilyInfo(String id)
    {
        Family family = null;

        Patient patient = this.patientRepository.getPatientById(id);
        if (patient != null) {
            // id belonged to a patient. Get patient's family
            family = this.familyRepository.getFamilyForPatient(patient);
        } else {
            // id is not a patient's id. Check if it is a family id
            family = this.familyRepository.getFamilyById(id);
        }

        if (family == null) {
            // id is not a family id, nor a patient's id
            this.logger.error("getFamilyInfo, id:[{}]. Id does not identify a family or a patient", id);
            return new JSONObject(true);
        }

        return family.getInformationAsJSON();
    }

    /**
     * Family page should aggregate medical reports of all its members.
     *
     * @param familyId if of family to get reports for
     * @return patient ids mapped to medical reports, which in turn are maps of report name to its link
     */
    // TODO: Who calls this?
    public Map<String, Map<String, String>> getReports(String familyId)
    {
        Family family = this.familyRepository.getFamilyById(familyId);
        if (family == null) {
            this.logger.error("Could not retrieve family with id [{}]", familyId);
            return new HashMap<>();
        }
        return family.getMedicalReports();
    }

    /**
     * Returns a patient's pedigree, which is the pedigree of a family that patient belongs to, or the patient's own
     * pedigree if the patient does not belong to a family.
     *
     * @param id must be a valid family id or a patient id
     * @return JSON of the data portion of a family or patient pedigree
     */
    public JSON getPedigree(String id)
    {
        try {
            return PedigreeUtils.getPedigree(id, this.utils);
        } catch (XWikiException ex) {
            this.logger.error("Error happend while retrieving pedigree of document with id {}. {}",
                id, ex.getMessage());
            return new JSONObject(true);
        }
    }

    /**
     * Verifies that a patient can be added to a family.
     *
     * @param thisId could be a family id or a patient id. If it is a patient id, finds the family that the patient
     *            belongs to. This family is the one into which the `otherId` patient is added to
     * @param otherId must be a valid patient id
     * @return {@link JSON} with 'validLink' field set to {@link false} if everything is ok, or {@link false} if the
     *         `otherId` patient is not linkable to `thisId` family. In case the linking is invalid, the JSON will also
     *         contain 'errorMessage' and 'errorType'
     */
    public JSON verifyLinkable(String thisId, String otherId)
    {
        try {
            return this.validation.canAddToFamily(thisId, otherId).asVerification();
        } catch (XWikiException ex) {
            return new JSONObject(true);
        }
    }

    /**
     * Performs several operations on the passed in data, and eventually saves it into appropriate documents.
     *
     * @param anchorId could be a family id or a patient id. If a patient does not belong to a family, there is no
     *            processing of the pedigree, and the pedigree is simply saved to that patient record. If the patient
     *            does belong to a family, or a family id is passed in as the `anchorId`, there is processing of the
     *            pedigree, which is then saved to all patient records that belong to the family and the family
     *            documents itself.
     * @param json part of the pedigree data
     * @param image svg part of the pedigree data
     * @return {@link JSON} with 'error' field set to {@link false} if everything is ok, or {@link false} if a known
     *         error has occurred. In case the linking is invalid, the JSON will also contain 'errorMessage' and
     *         'errorType'
     */
    public JSON processPedigree(String anchorId, String json, String image)
    {
        try {
            return this.processing.processPatientPedigree(anchorId, JSONObject.fromObject(json), image).asProcessing();
        } catch (Exception ex) {
            return new JSONObject(true);
        }
    }

    /**
     * Removes a patient from the family, modifying the both the family and patient records to reflect the change.
     *
     * @param id of the patient to delete
     */
    // TODO change to XWikiFamily api
    public void removeMember(String id)
    {
        this.processing.removeMember(id);
    }

    /**
     * Add a patient to a family.
     *
     * @param familyId to identify which family to add it
     * @param patientId to identify the patient to add
     * @return true if operation was successful
     */
    public boolean addPatientToFamily(String familyId, String patientId)
    {
        Patient patient = this.patientRepository.getPatientById(patientId);
        if (patient == null) {
            this.logger.error("Could not retrieve patient [{}]", patientId);
            return false;
        }
        Family patientsfamily = this.familyRepository.getFamilyForPatient(patient);
        if (patientsfamily != null) {
            this.logger.info("Patient [{}] is already associated with family [{}].", patientId, patientsfamily.getId());
            return false;
        }

        Family family = this.familyRepository.getFamilyById(familyId);
        if (family == null) {
            this.logger.error("Could not retrieve family [{}]", familyId);
            return false;
        }
        family.addMember(patient);
        return true;
    }

    /**
     * return a JSON object with a list of families that fit the input search criterion.
     *
     * @param input beginning of string of family
     * @param resultsLimit maximum number of results
     * @param requiredPermissions only families on which the user has requiredPermissions will be returned
     * @param returnAsJSON if true, the result is returned as JSON, otherwise as HTML
     * @return list of families
     */
    public String searchFamilies(String input, int resultsLimit, String requiredPermissions, boolean returnAsJSON)
    {
        return this.familyExport.searchFamilies(input, resultsLimit, requiredPermissions, returnAsJSON);
    }
}