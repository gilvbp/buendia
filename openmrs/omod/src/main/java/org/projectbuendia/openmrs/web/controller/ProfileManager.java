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

package org.projectbuendia.openmrs.web.controller;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.context.Context;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.view.RedirectView;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static org.openmrs.projectbuendia.Utils.eq;

/** The controller for the profile management page. */
@Controller
public class ProfileManager {
    // Global properties can be edited in Management > Advanced Settings.
    // The property keys here and those listed in config.xml should match.

    /** The global property that stores the filename of the current profile. */
    public static final String GLOBAL_PROPERTY_CURRENT_PROFILE = "projectbuendia.currentProfile";

    protected static Log log = LogFactory.getLog(ProfileManager.class);
    private final String PROFILE_DIR_PATH = "/usr/share/buendia/profiles";
    private final String VALIDATE_CMD = "buendia-profile-validate";
    private final String APPLY_CMD = "buendia-profile-apply";
    private File profileDir;

    /** This is executed every time a request is made and determines the profileDir to be used. */
    @ModelAttribute
    public void getProfileDir() {
        Properties config = Context.getRuntimeProperties();
        String configProfileDir = config.getProperty("profile_manager.profile_dir", PROFILE_DIR_PATH);
        profileDir = new File(configProfileDir);
    }

    @RequestMapping(value = "/module/projectbuendia/openmrs/profiles", method = RequestMethod.GET)
    public void get(HttpServletRequest request, ModelMap model) {
        String currentProfile = getCurrentProfile();
        model.addAttribute("currentProfile", currentProfile);
        model.addAttribute("authorized", authorized());
        model = queryParamsToModelAttr(request, model);
        if (!profileDir.exists()) {
            model.addAttribute("success", false);
            model.addAttribute("message", "The Profile Manager directory does not exist. It must reside on " +
                    "/usr/share/buendia/profiles or on a directory specified in openmrs-runtime.properties " +
                    "file under profile_manager.profile_dir directive.");
        } else {
            model.addAttribute("profiles", listProfiles());
        }
    }

    /** This method sends data to jsp in a more consistent way. */
    private ModelMap queryParamsToModelAttr(HttpServletRequest request, ModelMap model) {
        Map params = request.getParameterMap();
        Iterator paramIterator = params.entrySet().iterator();
        while (paramIterator.hasNext()) {
            Entry param = (Entry) paramIterator.next();
            String key = (String) param.getKey();
            String[] value = (String[]) param.getValue();
            model.addAttribute(key, value[0]);
        }
        return model;
    }

    private List<FileInfo> listProfiles() {
        List<FileInfo> files = new ArrayList<>();
        File[] profiles = profileDir.listFiles();
        if (profiles != null) {
            for (File file : profiles) {
                files.add(new FileInfo(file));
            }
            Collections.sort(files, new Comparator<FileInfo>() {
                public int compare(FileInfo a, FileInfo b) {
                    return -a.modified.compareTo(b.modified);
                }
            });
        }
        return files;
    }

    public static boolean authorized() {
        return Context.hasPrivilege("Manage Concepts") &&
            Context.hasPrivilege("Manage Forms");
    }

    @RequestMapping(value = "/module/projectbuendia/openmrs/profiles", method = RequestMethod.POST)
    public View post(HttpServletRequest request, HttpServletResponse response, ModelMap model) {
        if (!authorized()) {
            return new RedirectView("profiles.form");
        }

        if (request instanceof MultipartHttpServletRequest) {
            addProfile((MultipartHttpServletRequest) request, model);
        } else {
            String filename = request.getParameter("profile");
            String op = request.getParameter("op");
            if (filename != null) {
                File file = new File(profileDir, filename);
                if (file.isFile()) {
                    model.addAttribute("filename", filename);
                    if (eq(op, "Apply")) {
                        applyProfile(file, model);
                    } else if (eq(op, "Download")) {
                        downloadProfile(file, response);
                        return null;  // download the file, don't redirect
                    } else if (eq(op, "Delete")) {
                        deleteProfile(file, model);
                    }
                }
            }
        }
        return new RedirectView("profiles.form");  // reload this page with a GET request
    }

    /** Chooses a filename based on the given name, with a "-vN" suffix appended for uniqueness. */
    private String getNextVersionedFilename(String name) {
        // Separate the name into a sanitized base name and an extension.
        name = sanitizeName(name);
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            ext = name.substring(dot);
            name = name.substring(0, dot);
        }

        // Find the highest version number among all existing files named like "name-vN.ext".
        // If "name.ext" exists, it counts as version 1.
        String prefix = name + "-v";
        int highestVersion = 0;
        for (File file : profileDir.listFiles()) {
            int version = 0;
            String n = file.getName();
            if (eq(n, name + ext)) {
                version = 1;
            } else if (n.startsWith(prefix) && n.endsWith(ext)) {
                try {
                    version = Integer.parseInt(
                        n.substring(prefix.length(), n.length() - ext.length()));
                } catch (NumberFormatException e) { }
            }
            highestVersion = Math.max(version, highestVersion);
        }

        // Generate a unique new name, adding the next higher version number if necessary.
        if (highestVersion == 0) {
            return name + ext;
        } else {
            return prefix + (highestVersion + 1) + ext;
        }
    }

    /** Handles an uploaded profile. */
    private void addProfile(MultipartHttpServletRequest request, ModelMap model) {
        List<String> lines = new ArrayList<>();
        MultipartFile mpf = request.getFile("file");
        if (mpf != null) {
            String message = "";
            String filename = mpf.getOriginalFilename();
            try {
                File tempFile = File.createTempFile("profile", null);
                mpf.transferTo(tempFile);
                boolean exResult = execute(VALIDATE_CMD, tempFile, lines);
                if (exResult) {
                    filename = getNextVersionedFilename(filename);
                    File newFile = new File(profileDir, filename);
                    FileUtils.moveFile(tempFile, newFile);
                    model.addAttribute("success", true);
                    message = "Success adding profile: ";
                } else {
                    model.addAttribute("success", false);
                    message = "Error adding profile: ";
                }
            } catch (Exception e) {
                model.addAttribute("success", false);
                message = "Problem saving uploaded profile: ";
                lines.add(e.getMessage());
                log.error("Problem saving uploaded profile", e);
            } finally {
                model.addAttribute("operation", "add");
                model.addAttribute("message", message + filename);
                model.addAttribute("filename", filename);
                model.addAttribute("output", StringUtils.join(lines, "\n"));
            }
        }
    }

    /** Applies a profile to the OpenMRS database. */
    private void applyProfile(File file, ModelMap model) {
        List<String> lines = new ArrayList<>();
        if (execute(APPLY_CMD, file, lines)) {
            setCurrentProfile(file.getName());
            model.addAttribute("success", true);
            model.addAttribute("message", "Success applying profile: " + file.getName());
        } else {
            model.addAttribute("success", false);
            model.addAttribute("message", "Error applying profile: " + file.getName());
            model.addAttribute("output", StringUtils.join(lines, "\n"));
        }
    }

    /** Downloads a profile. */
    private void downloadProfile(File file, HttpServletResponse response) {
        response.setContentType("application/octet-stream");
        response.setHeader(
            "Content-Disposition", "attachment; filename=\"" + file.getName() + "\"");
        try {
            response.getOutputStream().write(
                Files.readAllBytes(Paths.get(file.getAbsolutePath())));
        } catch (IOException e) {
            log.error("Error downloading profile: " + file.getName(), e);
        }
    }

    /** Deletes a profile. */
    private void deleteProfile(File file, ModelMap model) {
        if (eq(file.getName(), getCurrentProfile())) {
            model.addAttribute("success", false);
            model.addAttribute("message", "Cannot delete the currently active profile.");
        } else if (file.delete()) {
            model.addAttribute("success", true);
            model.addAttribute("message", "Success deleting profile: " + file.getName());
        } else {
            model.addAttribute("success", false);
            model.addAttribute("message", "Error deleting profile: " + file.getName());
            log.error("Error deleting profile: " + file.getName());
        }
    }

    /**
     * Executes a command with one argument, returning true if the command succeeds.
     * Gathers the output from stdout and stderr into the provided list of lines.
     */
    private boolean execute(String command, File arg, List<String> lines) {
        ProcessBuilder pb = new ProcessBuilder(command, arg.getAbsolutePath());
        pb.redirectErrorStream(true);  // redirect stderr to stdout
        try {
            Process proc = pb.start();
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            proc.waitFor();
            return proc.exitValue() == 0;
        } catch (Exception e) {
            log.error("Exception while executing: " + command + " " + arg, e);
            lines.add(e.getMessage());
            return false;
        }
    }

    /** Sanitizes a string to produce a safe filename. */
    private String sanitizeName(String filename) {
        String[] parts = filename.split("/");
        return parts[parts.length - 1].replaceAll("[^A-Za-z0-9._-]", " ").replaceAll(" +", " ");
    }

    /** Gets the global property for the name of the current profile. */
    private String getCurrentProfile() {
        return Context.getAdministrationService().getGlobalProperty(
            GLOBAL_PROPERTY_CURRENT_PROFILE);
    }

    /** Sets the global property for the name of the current profile. */
    private void setCurrentProfile(String name) {
        Context.getAdministrationService().setGlobalProperty(
            GLOBAL_PROPERTY_CURRENT_PROFILE, name);
    }

    public class FileInfo {
        String name;
        Long size;
        Date modified;

        public FileInfo(File file) {
            name = file.getName();
            size = file.length();
            modified = new Date(file.lastModified());
        }

        public String getName() {
            return name;
        }
        public String getFormattedName() {
            return (name + "                              ").substring(0, 30);
        }
        public Long getSize() {
            return size;
        }
        public String getFormattedSize() {
            return String.format("%7d", size);
        }
        public Date getModified() {
            return modified;
        }
    }
}
