/*
 * Copyright (c) 2019 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.jersey.tools.plugins.releasenotes;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHMilestone;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.PagedIterable;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Mojo(name = "release-notes", defaultPhase = LifecyclePhase.POST_SITE)
public class ReleaseNotesMojo extends AbstractMojo {

    @Parameter(required = true, defaultValue = "2.29.1")
    private String releaseVersion;
    @Parameter(required = true, defaultValue = "eclipse-ee4j/jersey")
    private String githubApiUrl;
    @Parameter(required = true)
    private String githubLogin;
    @Parameter(required = false)
    private String githubToken;
    @Parameter(required = false)
    private String githubPassword;
    @Parameter(required = true, defaultValue = "false")
    private Boolean publishToGithub;
    @Parameter(required = true, defaultValue = "true")
    private Boolean dryRun;
    @Parameter(required = true)
    private String templateFilePath;
    @Parameter(required = true)
    private String releaseDate;
    @Parameter(required = true, defaultValue = "target/release-notes")
    private String releaseNotesFilePath;

    private static final String RELEASE_DATE_PATTERN = "@RELEASE_DATE@";
    private static final String LATEST_VERSION_PATTERN = "@LATEST_VERSION@";

    public void execute() throws MojoExecutionException, MojoFailureException {
        validateParameters();
        try {
            final GitHub github = githubPassword != null ? GitHub.connectUsingPassword(githubLogin, githubPassword) :
                    GitHub.connect(githubLogin, githubToken);
            final GHRepository repository = github.getRepository(githubApiUrl);
            final PagedIterable<GHMilestone> milestones = repository.listMilestones(GHIssueState.ALL);
            milestones.forEach(milestone -> {
                if (releaseVersion.equalsIgnoreCase(milestone.getTitle())) {
                    getLog().info(String.format("Milestone found for release version: %s", releaseVersion));
                    try {
                        final String releaseNotes = prepareReleaseNotes(milestone, repository);
                        getLog().info("Prepared release notes:");
                        getLog().info(releaseNotes);
                        if (Boolean.TRUE.equals(publishToGithub) && Boolean.FALSE.equals(dryRun)) {
                            getLog().info("Publishing release notes to GitHub");
                            publishReleaseNotes(releaseNotes, releaseVersion, repository);
                        } else {
                            getLog().info("Publishing to GitHub is disabled, skipping");
                        }
                        storeReleaseNotes(releaseNotes, templateFilePath, releaseVersion,
                                releaseDate, releaseNotesFilePath, dryRun, getLog());
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static String prepareReleaseNotes(GHMilestone milestone, GHRepository repository) throws IOException {
        final StringBuffer releaseNotes = new StringBuffer();
        final String pullRequestFormat = "<li>[<a href='%s'>Pull %d</a>] - %s</li>\n";
        final String issueFormat = "<li>[<a href='%s'>Issue %d</a>] - %s</li>\n";
        final List<String> releaseNotesLines = new ArrayList<>();
        final List<GHIssue> issues = repository.getIssues(GHIssueState.CLOSED, milestone);
        issues.stream().map(issue -> String.format(issue.isPullRequest() ? pullRequestFormat : issueFormat,
                issue.getHtmlUrl(), issue.getNumber(), issue.getTitle())).forEach(releaseNotesLines::add);
        releaseNotesLines.stream().sorted().forEach(releaseNotes::append);
        return releaseNotes.toString();
    }

    private static void publishReleaseNotes(String releaseNotes, String releaseVersion, GHRepository repository) throws IOException {
        repository.createRelease(releaseVersion).name(releaseVersion).body(releaseNotes).create();
    }

    private static void storeReleaseNotes(String releaseNotes, String templateFilePath,
                                          String releaseVersion, String releaseDate,
                                          String releaseNotesFilePath,
                                          Boolean dryRun, Log log) throws IOException {
        if (Files.notExists(Paths.get(templateFilePath))) {
            return;
        }
        final List<String> notesLines = new ArrayList<>();
        final List<String> lines = Files.readAllLines(Paths.get(templateFilePath), Charset.defaultCharset());
        for (final String line : lines) {
            if (line.contains(RELEASE_DATE_PATTERN)) {
                notesLines.add(line.replace(RELEASE_DATE_PATTERN, releaseDate));
            } else if (line.contains(LATEST_VERSION_PATTERN)) {
                notesLines.add(line.replace(LATEST_VERSION_PATTERN, releaseVersion));
            } else if (line.contains("<h2>Previous releases</h2>")) {
                notesLines.add("<h2>Pull requests and issues</h2>\n<ul>");
                notesLines.add(releaseNotes);
                notesLines.add("</ul>");
                notesLines.add(line);
            } else if (line.contains("<ul>")) {
                notesLines.add(line);
                notesLines.add(String.format("    <li><a href=\"%s.html\">Jersey %s Release Notes</a></li>", releaseVersion, releaseVersion));
            } else {
                notesLines.add(line);
            }
        }
        if (Boolean.FALSE.equals(dryRun)) {
            log.info(String.format("Storing release notes to file %s/%s.html", releaseNotesFilePath, releaseVersion));
            Files.createDirectories(Paths.get(releaseNotesFilePath));
            Files.write(Paths.get(String.format("%s/%s.html", releaseNotesFilePath, releaseVersion)), notesLines, Charset.defaultCharset());
        } else {
            log.info("Prepared release notes are not stored to file due to dryRun mode");
            log.info(String.format("File pathe to store release notes is: %s/%s.html", releaseNotesFilePath, releaseVersion));
        }
    }

    private void validateParameters() throws MojoFailureException {
        if (releaseVersion == null) {
            throw new MojoFailureException("releaseVersion shall be provided");
        }
        if (githubLogin == null) {
            throw new MojoFailureException("githubLogin shall be provided");
        }

        if (githubPassword == null && githubToken == null) {
            throw new MojoFailureException("either githubPassword or githubToken shall be provided");
        }
    }
}
