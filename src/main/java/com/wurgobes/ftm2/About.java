package com.wurgobes.ftm2;

import ij.gui.GenericDialog;
import ij.plugin.PlugIn;

public class About implements PlugIn {

    @Override
    public void run(String arg) {
        String content = "Developed by Martijn Gobes at the Holhbein Lab.\nMore information can be found at https://github.com/HohlbeinLab/FTM2\n" +
                "Current Version: 0.9.2";

        GenericDialog gd = new GenericDialog("About");
        gd.addMessage(content);

        gd.showDialog();
    }
}
