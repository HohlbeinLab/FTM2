package com.wurgobes.imagej;

import net.imglib2.type.numeric.RealType;
import org.scijava.Priority;
import org.scijava.command.Command;
import org.scijava.plugin.Plugin;

@Plugin(type = Command.class, menuPath = "Plugins>Faster Temporal Median>Use Opened Image and Run", label="FTM2_opened", priority = Priority.VERY_HIGH)
public class FTM2_use_opened_image< T extends RealType< T >>  implements Command {

    @Override
    public void run() {
        FTM2<T> temp = new FTM2<>(3);
        temp.run();
    }
}