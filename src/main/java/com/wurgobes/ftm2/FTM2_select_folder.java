package com.wurgobes.ftm2;

import ij.plugin.PlugIn;

import net.imagej.ops.OpService;
import net.imglib2.type.numeric.RealType;
import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

@Plugin(type = Command.class, menuPath = "Plugins>Faster Temporal Median>Select Folder and Run")
public class FTM2_select_folder< T extends RealType< T >>  implements Command {
    @Parameter
    private OpService opService;

    @Parameter
    private LogService logService;
    @Override
    public void run() {
        FTM2<T> temp = new FTM2<>(2, opService, logService);
        temp.run();
    }

}
