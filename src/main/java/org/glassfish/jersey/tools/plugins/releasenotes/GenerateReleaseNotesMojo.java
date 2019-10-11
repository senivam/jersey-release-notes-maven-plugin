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
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHMilestone;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.PagedIterable;

import java.io.IOException;
import java.util.List;

public class GenerateReleaseNotesMojo extends AbstractMojo {

    private String releaseVersion;
    private String githubApiUrl;
    private String githubLogin;
    private String githubToken;
    private String githubPassword;
    private Boolean publishToGithub;

    public String getReleaseVersion() {
        return releaseVersion;
    }

    public void setReleaseVersion(String releaseVersion) {
        this.releaseVersion = releaseVersion;
    }

    public String getGithubApiUrl() {
        return githubApiUrl;
    }

    public void setGithubApiUrl(String githubApiUrl) {
        this.githubApiUrl = githubApiUrl;
    }

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
                        if (Boolean.TRUE.equals(publishToGithub)) {
                            getLog().info("Publishing release notes to GitHub");
                            publishReleaseNotes(releaseNotes, releaseVersion, repository);
                        }
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
        final List<GHIssue> issues =repository.getIssues(GHIssueState.CLOSED, milestone);
        for (final GHIssue issue : issues) {
            releaseNotes.append(String.format(issue.isPullRequest() ? pullRequestFormat: issueFormat,
                    issue.getHtmlUrl(), issue.getNumber(), issue.getTitle()));
        }

        return releaseNotes.toString();
    }

    private static void publishReleaseNotes(String releaseNotes, String releaseVersion, GHRepository repository) throws IOException {
        repository.createRelease(releaseVersion).name(releaseVersion).body(releaseNotes).create();
    }

    private void validateParameters() throws MojoFailureException {
        if (releaseVersion == null || releaseVersion.length() == 0) {
            throw new MojoFailureException("releaseVersion shall be provided");
        }
        if (githubLogin == null || releaseVersion.length() == 0) {
            throw new MojoFailureException("githubLogin shall be provided");
        }

        if (githubPassword == null && githubToken == null) {
            throw new MojoFailureException("either githubPassword or githubToken shall be provided");
        }
    }
}
