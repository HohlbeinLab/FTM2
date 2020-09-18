package com.wurgobes.imagej;

import static java.lang.Double.compare;
import java.util.*;
import static org.apache.commons.lang3.ArrayUtils.swap;
import java.util.stream.Collectors;
import java.util.Arrays;
import java.util.List;

//https://stackoverflow.com/questions/1790360/median-of-medians-in-java
public class MiscFunctions {
    private MiscFunctions() {

    }
    
    public static int[] ShortToInt(short[] array) {
        int[] int_arr = new int[array.length];
        for(int i = 0; i < array.length; i++){
            int_arr[i] = (int) array[i];
        }
        return int_arr;
    }
    
    public static float[] ShortToFloat(short[] array) {
        float[] int_arr = new float[array.length];
        for(int i = 0; i < array.length; i++){
            int_arr[i] = (float) array[i];
        }
        return int_arr;
    }
    
    public static int SumInt(int[] array) {
        return Arrays.stream(array).sum();
    }
    
    public static int SumShort(short[] array) {
        int sum = 0;
        for(short b : array)
          sum += b;
        return sum;
    }
    
    public static int SumByte(byte[] array) {
        int sum = 0;
        for(byte b : array)
          sum += b;
        return sum;
    }
    public static int getMedian(short[] arr){
        Arrays.sort(arr);
        return arr[arr.length/2];
    }


}