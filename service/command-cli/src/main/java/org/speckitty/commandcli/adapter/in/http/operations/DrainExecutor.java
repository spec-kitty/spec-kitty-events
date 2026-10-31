package org.speckitty.commandcli.adapter.in.http.operations;

import java.util.concurrent.Executor;

/** Runs the end of a drain off the request thread (a platform thread in production, so the JVM exit is not tied to a request). */
public interface DrainExecutor extends Executor {}
