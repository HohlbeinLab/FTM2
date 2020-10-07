package com.wurgobes.ftm2;

import ij.plugin.PlugIn;
import net.imglib2.type.numeric.RealType;

public class FTM2_select_folder< T extends RealType< T >>  implements PlugIn {

    @Override
    public void run(String arg) {
        FTM2<T> temp = new FTM2<>(2);
        temp.run(arg);
    }

}
