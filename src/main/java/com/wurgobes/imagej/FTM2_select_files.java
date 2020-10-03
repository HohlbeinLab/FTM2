package com.wurgobes.imagej;

import net.imglib2.type.numeric.RealType;
import org.scijava.Priority;
import org.scijava.command.Command;
import org.scijava.plugin.Plugin;

@Plugin(type = Command.class, menuPath = "Plugins>Fast Temporal Median>Select Files and Run", label="FTM2", priority = Priority.VERY_HIGH)
public class FTM2_select_files< T extends RealType< T >>  implements Command {

    @Override
    public void run() {
        FTM2<T> temp = new FTM2<>(1);
        temp.run();

    }
}