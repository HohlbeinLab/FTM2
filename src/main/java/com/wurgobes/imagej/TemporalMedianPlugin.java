package com.wurgobes.imagej;


import net.imagej.ImageJ;
import org.scijava.app.StatusService;
import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.ui.UIService;

import net.imagej.Dataset;
import net.imagej.ImgPlus;
import net.imglib2.img.Img;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedShortType;

import java.io.IOException;

@Plugin(type = Command.class, headless = true,
menuPath = "Plugins>Process>Temporal Median Background Subtraction")
public class TemporalMedianPlugin implements Command{

	@Parameter
	private LogService log;

	@Parameter
	private StatusService statusService;
	
	@Parameter
	private UIService uiService;
	
	@Parameter
	private Dataset currentData;

	@Parameter(label = "Median window", description = "the frames for medan")
	private short window;

	@Parameter(label = "Added offset", description = "offset added to the new image")
	private short offset;

	public static void main(final String... args) throws IOException {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
	}

	@Override
	public void run() {
		RealType<?> tempPixel = currentData.firstElement();
		if (tempPixel.getBitsPerPixel()!=16) {
			log.error("bits per pixel not equal to 16");
			return;
		}
		@SuppressWarnings("unchecked")
		final Img<UnsignedShortType> img = (ImgPlus<UnsignedShortType>) currentData.getImgPlus();		
		final long[] dims = new long[img.numDimensions()];
		img.dimensions(dims);
		//sanity check on the input
		if (window >= dims[2]) {
			window = (short) dims[2];
			if (window % 2 == 0) {window--;}
			log.warn("Window is larger than largest dimension. Reducing window to " + window);
		} else if (window % 2 == 0) {
			window++;
			log.warn("No support for even windows. Window = " + window);
		}
		long start = System.currentTimeMillis();
		TemporalMedian.main(img, window);
		long end = System.currentTimeMillis();
		long RunTime = end-start;
		log.info("Runtime of TemporalMedian = "+RunTime+"ms");
	}
}
