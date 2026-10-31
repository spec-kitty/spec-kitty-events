package org.speckitty.commandcli.config;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;

/** The Spring application of the write service. Scanning covers the configuration and the HTTP adapter only. */
@SpringBootApplication(scanBasePackages = {"org.speckitty.commandcli.config", "org.speckitty.commandcli.adapter.in.http"})
@ImportRuntimeHints(CommandCliHints.class)
public class CommandCliApplication {}
