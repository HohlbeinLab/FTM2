package com.wurgobes.ftm2;
/* Median finding histogram 
(c) 2019 Rolf Harkes, Netherlands Cancer Institute.
Based on the Fast Temporal Median Filter for ImageJ by the Milstein Lab.
This class would run on every pixel in an image and is therefore kept minimal.
The medianFindingHistogram is created once and then updated with new values.

Known limitations for increased speed
* only odd windows where the median is the center value

Known limitations due to usage of the short datatype:
* no more than 32767 unique values allowed (a SMLM experiment would usually have about 1000 unique values)
* no more than 32767 equal values allowed  (the window is usually 501)

This software is released under the GPL v3. You may copy, distribute and modify 
the software as long as you track changes/dates in source files. Any 
modifications to or software including (via compiler) GPL-licensed code 
must also be made available under the GPL along with build & install instructions.
https://www.gnu.org/licenses/gpl-3.0.en.html

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
 */
 
public class MedianHistogram {
    private final int window;

    private final int[] hist; //Gray-level histogram init at 0

    private int median;//The median of this pixel
    private int aux;   //Marks the position of the median pixel in the column of the histogram, starting with 1

    /**
     * @param window window width of the median filter
     * @param maxVal maximum value occurring in the input data
     */
    public MedianHistogram(int window, int maxVal) {
        this.window = window;

        //0 indexed sorted array has median at this position.
        int windowC = (window - 1) / 2; //0 indexed sorted array has median at this position.
        hist = new int[maxVal + 1]; //Gray-level histogram init at 0

        hist[0] = window;
        aux = windowC + 1;
        median = 0;

        history = new int[window];
        hi = 0;
    }

    /**
     * Get current median.
     * It only makes sense to call this after adding {@code windowWidth} pixels.
     */
    public int get() {
        return median;
    }
    public int getaux() {
    	return aux;
    }
    public int[] gethist() {
    	return hist;
    }
    /**
     * Add new value to histogram
     */
    public void add(final int pixel2) {
        final int pixel = record(pixel2);

        hist[pixel]--; //Removing old pixel
        hist[pixel2]++; //Adding new pixel
        if (!(
                (pixel > median && pixel2 > median)
                        || (pixel < median && pixel2 < median)
                        || (pixel == median && pixel2 == median)
        )) //Add and remove the same pixel, or pixel from the same side, the median doesn't change
        {
            int j = median;
            if ((pixel2 > median) && (pixel < median)) //The median goes right
            {
                if (hist[median] == aux) //The previous median was the last pixel of its column in the histogram, so it changes
                {
                    j++;
                    while (hist[j] == 0) //Searching for the next pixel
                    {
                        j++;
                    }
                    median = j;
                    aux = 1; //The median is the first pixel of its column
                } else {
                    aux++; //The previous median wasn't the last pixel of its column, so it doesn't change, just need to mark its new position
                }
            } else if ((pixel > median) && (pixel2 < median)) //The median goes left
            {
                if (aux == 1) //The previous median was the first pixel of its column in the histogram, so it changes
                {
                    j--;
                    while (hist[j] == 0) //Searching for the next pixel
                    {
                        j--;
                    }
                    median = j;
                    aux = hist[j]; //The median is the last pixel of its column
                } else {
                    aux--; //The previous median wasn't the first pixel of its column, so it doesn't change, just need to mark its new position
                }
            } else if (pixel2 == median) //new pixel = last median
            {
                if (pixel < median) //old pixel < last median, the median goes right
                {
                    aux++; //There is at least one pixel above the last median (the one that was just added), so the median doesn't change, just need to mark its new position
                }                                //else, absolutely nothing changes
            } else //pixel==median, old pixel = last median
            {
                if (pixel2 > median) //new pixel > last median, the median goes right
                {
                    if (aux == (hist[median] + 1)) //The previous median was the last pixel of its column, so it changes
                    {
                        j++;
                        while (hist[j] == 0) //Searching for the next pixel
                        {
                            j++;
                        }
                        median = j;
                        aux = 1; //The median is the first pixel of its column
                    }
                    //else, absolutely nothing changes
                } else //pixel2<median, new pixel < last median, the median goes left
                {
                    if (aux == 1) //The previous median was the first pixel of its column in the histogram, so it changes
                    {
                        j--;
                        while (hist[j] == 0) //Searching for the next pixel
                        {
                            j--;
                        }
                        median = j;
                        aux = hist[j]; //The median is the last pixel of its column
                    } else {
                        aux--; //The previous median wasn't the first pixel of its column, so it doesn't change, just need to mark its new position
                    }
                }
            }
        }
    }

    private final int[] history;
    private int hi;

    private int record(int value) {
        final int old = history[hi];
        history[hi] = value;
        if (++hi >= window)
            hi = 0;
        return old;
    }
}