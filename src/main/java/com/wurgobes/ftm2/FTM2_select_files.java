package com.wurgobes.ftm2;

import ij.plugin.PlugIn;
import net.imglib2.type.numeric.IntegerType;
import net.imglib2.type.numeric.RealType;

public class FTM2_select_files< T extends RealType< T >>  implements PlugIn {

    @Override
    public void run(String arg) {
        FTM2<T> temp = new FTM2<>(1);
        temp.run(arg);

    }
}