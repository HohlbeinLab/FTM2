package com.wurgobes.ftm2;

import ij.gui.GenericDialog;


import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

@Plugin(type = Command.class, menuPath = "Plugins>Faster Temporal Median>About Faster Temporal Median...")
public class About implements Command {

    @Parameter
    private LogService log;

    @Override
    public void run() {
        log.info("Faster Temporal Median About");
        String content = "Developed by Martijn Gobes at the Holhbein Lab.\nMore information can be found at https://github.com/HohlbeinLab/FTM2\n" +
                "Current Version: 0.9.9.3";

        GenericDialog gd = new GenericDialog("About");
        gd.addMessage(content);

        gd.showDialog();
    }


}
