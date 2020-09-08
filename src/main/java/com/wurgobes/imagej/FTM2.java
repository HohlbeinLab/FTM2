package com.wurgobes.imagej;


import ij.*;
import ij.gui.GenericDialog;
import ij.plugin.filter.PlugInFilter;
import ij.process.ImageProcessor;

import org.scijava.command.Command;

import net.haesleinhuepf.clij.CLIJ;
import net.haesleinhuepf.clij.clearcl.ClearCLBuffer;
import net.haesleinhuepf.clij2.CLIJ2;


import net.imagej.ops.Op;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import io.scif.services.DatasetIOService;
import org.scijava.ui.UIService;
import java.io.File;
import java.io.IOException;
import ij.io.Opener;
import ij.io.OpenDialog;


@Plugin(type = Command.class, menuPath = "Plugins>Fast Temporal Median 2")
public class FTM2<T> implements PlugInFilter {
    
        @Parameter
	private ImagePlus image;
                
        @Parameter
        private ImageStack stack;
        

        
	@Override
	public int setup(String arg, ImagePlus imp) {
		if (arg.equals("about")) {
                        IJ.showMessage("TODO ABOUT");
			return DONE;
		}
                
                boolean done;
                do {
                    done = true;
                    try {
                        stack = imp.getStack();                                             
                        IJ.showMessage("succes, got a stack");
                    } catch (Exception e) {
                        done = false;                       
                        String filePath = new OpenDialog("Please select a stack").getPath();                       
                        imp = new Opener().openImage(filePath);
                        imp.show();
                    }
                } while(!done) ;

                image = new ImagePlus("", stack.getProcessor(1)) ; 
                image.show();
                
		return DOES_8G + DOES_16 + DOES_32 + STACK_REQUIRED;
	}

	@Override
	public void run(ImageProcessor ip) {
            
                
                CLIJ2 clij2 = CLIJ2.getInstance();

                // conversion
                ClearCLBuffer input = clij2.push(image);
                ClearCLBuffer output = clij2.create(input);

                // blur
                float sigma = 20;
                clij2.gaussianBlur(input, output, sigma, sigma);

                ImagePlus result = clij2.pull(output);
                result.show();

                // free memory afterwards
                input.close();
                output.close();
	}



	public static void main(String[] args) throws Exception {

                final ImageJ IJ_Instance = new ImageJ();
                

		ImagePlus image = IJ.openImage("C:\\Users\\Martijn\\Desktop\\Thesis2020\\ImageJ\\test_images\\stack_small.tif");
		image.show();

		IJ.runPlugIn(FTM2.class.getName(), "");
	}
}