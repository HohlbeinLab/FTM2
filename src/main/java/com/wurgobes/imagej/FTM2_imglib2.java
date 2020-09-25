package com.wurgobes.imagej;

import fiji.util.gui.GenericDialogPlus;
import ij.IJ;
import ij.ImagePlus;
import ij.plugin.FolderOpener;
import net.imagej.Dataset;
import net.imagej.ImageJ;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import org.scijava.Priority;
import org.scijava.app.StatusService;
import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.ui.UIService;

import io.scif.img.ImgIOException;

import java.io.File;
import java.io.IOException;

import io.scif.config.SCIFIOConfig;
import io.scif.config.SCIFIOConfig.ImgMode;
import io.scif.img.ImgOpener;

import java.util.ArrayList;

@Plugin(type = Command.class, headless = true, menuPath = "Plugins>Faster Temporal Median 2", label="FTM2_imglib", priority = Priority.VERY_HIGH)

public class FTM2_imglib2 implements Command {
    //@Parameter
    public static String sourceDirectory="C:\\Users\\Martijn\\Desktop\\Thesis2020\\ImageJ\\test_images\\test_folder"; //Change before release

    //@Parameter
    public static String outputDirectory="C:\\Users\\Martijn\\Desktop\\Thesis2020\\ImageJ\\test_images\\output";//Change before release

    private String target_dir;

    public static int window = 50;
    public static int start = 1;
    public static int end = 0;

    //@Parameter
    private int total_size = 0;


    private boolean all_fits = false;

    private long total_disk_size = 0;

    private final long max_bytes = Runtime.getRuntime().maxMemory();

    private int bit_depth;

    private Img<UnsignedShortType> imageData;

    private Img<UnsignedShortType> test;

    private final ArrayList<Img<UnsignedShortType>> imageList = new ArrayList<>();
    private final ArrayList<Integer> slice_intervals = new ArrayList<>();
    private RandomAccessibleInterval<UnsignedShortType> test_rando = null;

    @Parameter
    private LogService log;

    @Parameter
    private StatusService statusService;

    @Parameter
    private UIService uiService;

    //@Parameter
    private Dataset currentData;

    @Parameter
    private ImageJ ij;




    public  void setup()
    throws ImgIOException
    {

        boolean pre_loaded_image = currentData != null;

        GenericDialogPlus gd = new GenericDialogPlus("Register Virtual Stack");

        gd.addDirectoryField("Source directory", sourceDirectory, 50);
        gd.addDirectoryField("Output directory", outputDirectory, 50);
        gd.addNumericField("Window size", window, 0);
        gd.addNumericField("Begin", start, 0);
        gd.addNumericField("End (0 for all)", end, 0);
        gd.addCheckbox("Use open image?", pre_loaded_image);

        gd.showDialog();

        // Exit when canceled
        if (gd.wasCanceled())
            return;

        sourceDirectory = gd.getNextString();
        outputDirectory = gd.getNextString();
        window = (int)gd.getNextNumber();
        start = (int)gd.getNextNumber();
        end = (int)gd.getNextNumber();
        pre_loaded_image = gd.getNextBoolean();


        //@Parameter
        String source_dir = sourceDirectory;
        target_dir = outputDirectory;


        if (!pre_loaded_image && null == source_dir) {
            IJ.error("Error: No source directory was provided.");
            return;
        }
        if (null == target_dir) {
            IJ.error("Error: No output directory was provided.");
            return;
        }

        if(!pre_loaded_image){
            source_dir = source_dir.replace('\\', '/');
            if (!source_dir.endsWith("/")) source_dir += "/";
        }

        target_dir = target_dir.replace('\\', '/');
        if (!target_dir.endsWith("/")) target_dir += "/";




        if(!pre_loaded_image && !(new File(source_dir)).exists()){
            IJ.error("Error: source directory " + source_dir + " does not exist.");
            return;
        }


        if (!(new File(target_dir)).exists()){
            if(!new File(target_dir).mkdir()) {
                IJ.error("Error: Failed to create target directory " + target_dir);
            }
        }




        IJ.showStatus("Loading data");

        boolean any_too_big = false;

        if(!pre_loaded_image){


            File[] listOfFiles = new File(source_dir).listFiles();
            assert listOfFiles != null;


            for(File file: listOfFiles){
                total_disk_size += file.length();
                if (file.length() > max_bytes) {
                    any_too_big = true;
                }
            }
            all_fits = total_disk_size < max_bytes/ 3;
            //all_fits = false;
            if(all_fits) { // Everything together fits into RAM
                ImagePlus temp_img = new FolderOpener().openFolder(source_dir);
                imageData = ImageJFunctions.wrapReal(temp_img);

                int current_stack_size = (int) ( imageData.size()/ imageData.dimension(0)/ imageData.dimension(1));
                total_size += current_stack_size;
                System.out.println("Loaded opened image with " + current_stack_size + " slices with size " + total_disk_size + " as normal stack");

                test_rando = imageData;
            } else { //the sum of files wont fit into memory
                ImgOpener imgOpener = new ImgOpener();
                SCIFIOConfig config = new SCIFIOConfig();
                config.imgOpenerSetImgModes( ImgMode.CELL );
                config.imgOpenerSetOpenAllImages(true);
                int Stack_no = 0;

                for (int i = 0; i < listOfFiles.length; i++) {
                    if (listOfFiles[i].isFile() && listOfFiles[i].getName().endsWith(".tif")) {
                        imageList.add((Img<UnsignedShortType>) imgOpener.openImgs(listOfFiles[i].getPath(), config).get(0));
                        int current_stack_size = (int) ( imageList.get(0).size()/ imageList.get(0).dimension(0)/ imageList.get(0).dimension(1));
                        slice_intervals.add(current_stack_size + total_size);
                        total_size += current_stack_size;
                        Stack_no++;

                        System.out.println(i + ", " + listOfFiles[i].getPath() + ", " + current_stack_size + " slices as virtual stack");
                    }
                }
                test_rando = ij.op().transform().concatenateView(imageList, 2);

                System.out.println("Loaded " + Stack_no  + " images with total " + total_size + " slices");
            }



            ImageJFunctions.show(test_rando);

        } else {
            test_rando = (Img<UnsignedShortType>) currentData.getImgPlus();

            int current_stack_size = (int) imageData.size();
            total_size += current_stack_size;
            System.out.println("Loaded opened image with " + current_stack_size+ " slices with size " + total_disk_size + " as normal stack");
        }


        bit_depth = 16;

        if(total_size <= 1){
            IJ.error("Error: Stack must have size larger than 1.");
        }


        if(end == 0) end = total_size;
        if(window > total_size) window = total_size;

        if (window >= total_size) {
            window = (short) total_size;
            if (window % 2 == 0) {window--;}
            log.warn("Window is larger than largest dimension. Reducing window to " + window);
        } else if (window % 2 == 0) {
            window++; // otherwise last frame is skipped when integer division is applied
        }

        System.gc();

    }

    public void runMedian(){


        final long[] dims = new long[imageData.numDimensions()];
        imageData.dimensions(dims);

        long start = System.currentTimeMillis();
        log.info("Started calculation");
        TemporalMedian.main(imageData, window);

        ImageJFunctions.show( imageData );

        //IJ.run("Enhance Contrast", "saturated=0.0");
        long end = System.currentTimeMillis();
        long RunTime = end-start;
        log.info("Runtime of TemporalMedian = "+RunTime+"ms");


        // 22/09
        // slightly different gpu implementation for smaller stacks
        // 1764.552s(30m) on 25 GB file
        // xxxxms on 5x 1.5k large frame virtual
        // 5571ms on 1.5k large frame normal
        // 999ms on 20k normal
        // 192ms on 400 normal
    }

    @Override
    public void run() {
        setup();
        runMedian();
    }

    public static void main(final String... args) throws IOException {
        final ImageJ ij = new ImageJ();
        ij.ui().showUI();
        ij.command().run(FTM2_imglib2.class, true);
    }
}