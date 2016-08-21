/**
 * Copyright (C) 2015 Michael Schnell. All rights reserved. 
 * http://www.fuin.org/
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option) any
 * later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library. If not, see http://www.gnu.org/licenses/.
 */
package org.fuin.esmp;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DaemonExecutor;
import org.apache.commons.exec.DefaultExecuteResultHandler;
import org.apache.commons.exec.OS;
import org.apache.commons.exec.PumpStreamHandler;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts the event store.
 * 
 */
@Mojo(name = "start", defaultPhase = LifecyclePhase.PRE_INTEGRATION_TEST, requiresProject = false)
public final class EventStoreStartMojo extends AbstractEventStoreMojo {

    private static final Logger LOG = LoggerFactory
            .getLogger(EventStoreStartMojo.class);

    /**
     * Name of the executable or shell script to start the event store. Defaults
     * to the OS specific name for Windows, Linux and Mac OS families. Other OS
     * families will cause an error if this value is not set.
     * 
     */
    @Parameter(name = "command")
    private String command;

    /**
     * Command line arguments to pass to the executable. If no arguments are set
     * this defaults to <code>--mem-db=TRUE</code>.
     * 
     */
    @Parameter(name = "arguments")
    private String[] arguments;

    /**
     * Number of times to wait for the server until it's up and running. After
     * this time passed, the build will fail. This means the mojo will wait
     * <code>maxWaitCycles</code> * <code>sleepMs</code> milliseconds for the
     * server to finish it's startup process. Defaults to 20 times.
     * 
     */
    @Parameter(name = "max-wait-cycles", defaultValue = "20")
    private int maxWaitCycles = 20;

    /**
     * Number of milliseconds to sleep while waiting for the server. This means
     * the mojo will wait <code>maxWaitCycles</code> * <code>sleepMs</code>
     * milliseconds for the server to finish it's startup process. Defaults to
     * 500 ms.
     * 
     */
    @Parameter(name = "sleep-ms", defaultValue = "500")
    private int sleepMs = 500;

    /**
     * Message from the event store log to wait for.
     * 
     */
    @Parameter(name = "up-message", defaultValue = "'admin' user account has been created")
    private String upMessage = "'admin' user account has been created";

    @Override
    protected final void executeGoal() throws MojoExecutionException {
        init();

        LOG.info("command={}", command);
        LOG.info("arguments={}", Arrays.toString(arguments));

        final CommandLine cmdLine = createCommandLine();
        final DefaultExecuteResultHandler resultHandler = new DefaultExecuteResultHandler();
        final DaemonExecutor executor = new DaemonExecutor();
        try {
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            final PumpStreamHandler psh = new PumpStreamHandler(bos);
            executor.setStreamHandler(psh);
            executor.setWorkingDirectory(getEventStoreDir());
            executor.execute(cmdLine, resultHandler);
            final List<String> messages = waitForHttpServer(resultHandler, bos);
            logDebug(messages);
            final String pid = extractPid(messages);
            LOG.info("Event store process ID: {}", pid);
            writePid(pid);
        } catch (final IOException ex) {
            throw new MojoExecutionException(
                    "Error executing the command line: " + cmdLine, ex);
        }
    }

    private List<String> waitForHttpServer(
            final DefaultExecuteResultHandler resultHandler,
            final ByteArrayOutputStream bos) throws MojoExecutionException {

        // Wait for result
        int wait = 0;
        while ((wait++ < maxWaitCycles) && !resultHandler.hasResult()
                && !bos.toString().contains(upMessage)) {
            sleep(sleepMs);
        }

        if (bos.toString().contains(upMessage)) {
            // Success
            return asList(bos.toString());
        }

        // Failure
        final List<String> messages = asList(bos.toString());
        logError(messages);

        // Exception
        if (resultHandler.hasResult()) {
            throw new MojoExecutionException(
                    "Error starting the server. Exit code="
                            + resultHandler.getExitValue(),
                    resultHandler.getException());
        }
        // Timeout
        throw new MojoExecutionException(
                "Waited too long for the server to start!");

    }

    private void sleep(final int ms) {
        try {
            Thread.sleep(ms);
        } catch (final InterruptedException ex) {
            LOG.info("Interrupted while sleeping", ex);
        }
    }

    private String extractPid(final List<String> messages)
            throws MojoExecutionException {
        if (messages.size() == 0) {
            throw new MojoExecutionException(
                    "Starting the event store didn't return any messages");
        }
        final String first = messages.get(0);
        // Prefix looks like this: [19648,10,12:47:52.297]
        final int p0 = first.indexOf('[');
        if (p0 == -1) {
            throw new MojoExecutionException(
                    "Couldn't locate the starting bracket '[': " + first);
        }
        final int p1 = first.indexOf(',', p0 + 1);
        if (p1 == -1) {
            throw new MojoExecutionException(
                    "Couldn't locate the ending comma ',': " + first);
        }
        return first.substring(p0 + 1, p1);
    }

    private void init() throws MojoExecutionException {

        // Supply variables that are OS dependent
        if (OS.isFamilyWindows()) {
            if (command == null) {
                // For some strange reasons this does not work without the
                // path...
                command = getEventStoreDir() + File.separator
                        + "EventStore.ClusterNode.exe";
            }
        } else if (OS.isFamilyUnix()) {
            if (command == null) {
                command = "./run-node.sh";
            }
        } else if (OS.isFamilyMac()) {
            if (command == null) {
                command = "./run-node.sh";
            }
        } else {
            if (command == null) {
                throw new MojoExecutionException(
                        "Unknown OS - You must use the 'command' parameter");
            }
        }

        // Use in-memory mode if nothing else is set
        if (arguments == null) {
            arguments = new String[1];
            arguments[0] = "--mem-db=TRUE";
        }

    }

    private CommandLine createCommandLine() throws MojoExecutionException {
        final CommandLine cmdLine = new CommandLine(command);
        if (arguments != null) {
            for (final String argument : arguments) {
                cmdLine.addArgument(argument);
            }
        }
        return cmdLine;
    }

    /**
     * Returns the name of the executable or shell script to start the event
     * store.
     * 
     * @return Executable name.
     */
    public final String getCommand() {
        return command;
    }

    /**
     * Sets the name of the executable or shell script to start the event store.
     * 
     * @param command
     *            Executable name to set.
     */
    public final void setCommand(final String command) {
        this.command = command;
    }

    /**
     * Returns the command line arguments to pass to the executable.
     * 
     * @return Command line arguments.
     */
    public final String[] getArguments() {
        return arguments;
    }

    /**
     * Sets the command line arguments to pass to the executable.
     * 
     * @param arguments
     *            Arguments to set
     */
    public final void setArguments(final String[] arguments) {
        this.arguments = arguments;
    }

}
