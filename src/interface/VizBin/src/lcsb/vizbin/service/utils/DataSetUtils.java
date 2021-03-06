package lcsb.vizbin.service.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JProgressBar;

import lcsb.vizbin.InvalidArgumentException;
import lcsb.vizbin.UnhandledOSException;
import lcsb.vizbin.data.DataSet;
import lcsb.vizbin.data.Sequence;
import lcsb.vizbin.service.DataSetFactory;
import lcsb.vizbin.service.utils.pca.IPrincipleComponentAnalysis;
import lcsb.vizbin.service.utils.pca.PrincipleComponentAnalysisEJML;
import lcsb.vizbin.service.utils.pca.PrincipleComponentAnalysisMtj;
import no.uib.cipr.matrix.NotConvergedException;

import org.apache.log4j.Logger;

public class DataSetUtils {
	static Logger						logger						= Logger.getLogger(DataSetUtils.class);

	static String						tsneComand				= "./bh_tsne";

	static Integer					usedVal[][]				= new Integer[256][];

	static int							alphabetSize			= 4;

	static boolean					isDataSetCreated	= false;

	private static DataSet	dataSet						= null;

	// Tomasz Sternal - communication between DataSetUtils and ClusterPannel about
	// pointList
	// and polygonPoints is completely unnecessary
	// private static ArrayList<Sequence> pointList = null;
	// private static List<Point2D> polygonPoints = null;
	private static JFrame		drawingFrame			= null;

	public static void createKmers(DataSet dataSet, int k, boolean mergeRevComp) throws InvalidArgumentException {
		int totalKmers = 0;
		Integer totalKmersIgnored = 0;
		for (Sequence sequence : dataSet.getSequences()) {
			if (sequence.getKmers(k) == null) {
				kmersResult res = createKmers(sequence, k, mergeRevComp, totalKmersIgnored);
				Integer result[] = res.getResult();
				totalKmersIgnored = res.getNumKmersRemoved();
				totalKmers += sequence.getDna().length() + 1 - k;
				sequence.setKmers(k, result);
			}
		}
		if (totalKmersIgnored > 0) {
			JOptionPane.showMessageDialog(
					null, "Total number of kmers: " + totalKmers + ".\n" + totalKmersIgnored + " (" + Math.round(totalKmersIgnored * 10000.0 / totalKmers) / 100.0
							+ "%) kmers ignored since containing unknown letters.");
		}
	}

	protected static void createMergedKmerTabl(int k) {
		logger.debug("Creating usedVal for k=" + k);
		int maxVal = (int) Math.pow(alphabetSize, k);

		Integer tmpUsedVal[] = new Integer[maxVal];

		for (int i = 0; i < maxVal; i++) {
			int val = i;
			int revVal = getRevVal(val, k);
			tmpUsedVal[i] = Math.min(val, revVal);
		}
		usedVal[k] = tmpUsedVal;
	}

	private static int getRevVal(int val, int k) {
		int result = 0;
		for (int i = 0; i < k; i++) {
			result = result * alphabetSize + (alphabetSize - 1 - val % alphabetSize);
			val = val / alphabetSize;
		}
		return result;
	}

	/*
	 * Returns number of occurrences of every type of k-mers, and counts number of
	 * k-mers that will be ingored.
	 * 
	 * k-mers are considered words in 4-base system. For example: CGTA = 1230_(4)=
	 * 1*64 + 2*16 + 3*4 + 0*1 = 108 (dec)
	 * 
	 * @param sequence - currently analyzed sequence
	 * 
	 * @param k - number of nucleotides in one k-mer
	 * 
	 * @mergeRevComp - determines whether all sequences counts will be returned or
	 * only those that appeared at lest once
	 * 
	 * @kmersRemoved Number of k-mers containing unknown letters
	 */
	public static kmersResult createKmers(Sequence sequence, int k, boolean mergeRevComp, Integer kmersRemoved) throws InvalidArgumentException {
		if (usedVal[k] == null) {
			createMergedKmerTabl(k);
		}

		int maxVal = (int) Math.pow(alphabetSize, k);

		Integer[] result = new Integer[maxVal];
		for (int i = 0; i < maxVal; i++) {
			if (mergeRevComp) {
				result[usedVal[k][i]] = 1;
			} else
				result[i] = 1;
		}
		String dnaSequence = sequence.getDna();
		int val = 0;

		int lastUnknownLetterPos = -1;

		for (int i = 0; i < dnaSequence.length(); i++) {
			try {
				val = (val * alphabetSize + nucleotideVal(dnaSequence.charAt(i))) % maxVal;
			} catch (InvalidArgumentException e) {
				lastUnknownLetterPos = i;
				val = 0;
			}
			if (i >= k - 1 && i - lastUnknownLetterPos < k) {
				kmersRemoved++;
			} else {
				if (mergeRevComp)
					result[usedVal[k][val]]++;
				else
					result[val]++;
			}
		}

		if (k <= dnaSequence.length()) {
			if (mergeRevComp)
				result[usedVal[k][val]]++;
			else
				result[val]++;
		}

		if (mergeRevComp) {
			int counter = 0;
			for (int j = 0; j < maxVal; j++) {
				if (result[j] != null) {
					counter++;
				}
			}
			Integer[] mergedResult = new Integer[counter];
			int l = 0;
			for (int j = 0; j < maxVal; j++) {
				if (result[j] != null)
					mergedResult[l++] = result[j];
			}
			kmersResult res = new kmersResult(mergedResult, kmersRemoved);
			return res;
		} else {
			kmersResult res = new kmersResult(result, kmersRemoved);
			return res;
		}
	}

	private static int nucleotideVal(char charAt) throws InvalidArgumentException {
		switch (charAt) {
			case ('A'):
				return 0;
			case ('C'):
				return 1;
			case ('G'):
				return 2;
			case ('T'):
				return 3;
			case ('a'):
				return 0;
			case ('c'):
				return 1;
			case ('g'):
				return 2;
			case ('t'):
				return 3;
			default:
				throw new InvalidArgumentException("Invalid nucleotide: " + charAt);
		}
	}

	public static void normalizeDescVectors(DataSet dataSet, int k) {
		for (Sequence sequence : dataSet.getSequences()) {
			normalizeDescVector(sequence, k);
		}
	}

	public static void normalizeDescVector(Sequence sequence, int k) {
		Integer[] map = sequence.getKmers(k);

		int maxVal = map.length;

		double descVector[] = new double[maxVal];
		sequence.setDescVector(descVector);

		double counter = 0;
		for (int i = 0; i < maxVal; i++) {
			counter += map[i];
		}

		for (int i = 0; i < maxVal; i++) {
			descVector[i] = map[i] / counter;
		}
	}

	public static void createClrData(DataSet dataSet) {
		int vectorLength = dataSet.getSequences().get(0).getDescVector().length;
		double meanLn[] = new double[vectorLength];
		for (Sequence sequence : dataSet.getSequences()) {
			for (int i = 0; i < vectorLength; i++)
				meanLn[i] += Math.log(sequence.getDescVector()[i]);
		}
		for (int i = 0; i < vectorLength; i++)
			meanLn[i] /= dataSet.getSequences().size();

		for (Sequence sequence : dataSet.getSequences()) {
			double clrVector[] = new double[vectorLength];
			for (int i = 0; i < vectorLength; i++)
				clrVector[i] = Math.log(sequence.getDescVector()[i]) - meanLn[i];
			sequence.setClrVector(clrVector);
		}

	}

	public static void computePca(DataSet dataSet, int columns, PcaType pcaType) throws InvalidArgumentException {
		IPrincipleComponentAnalysis pca = null;
		if (pcaType.equals(PcaType.MTJ)) {
			pca = new PrincipleComponentAnalysisMtj();
		} else if (pcaType.equals(PcaType.EJML)) {
			pca = new PrincipleComponentAnalysisEJML();
		} else {
			throw new InvalidArgumentException("Unknown pca type: " + pcaType);
		}

		pca.setup(dataSet.getSequences().size(), dataSet.getSequences().get(0).getClrVector().length);
		for (Sequence sequence : dataSet.getSequences()) {
			pca.addSample(sequence.getClrVector());
		}
		try {
			pca.computeBasis(columns);
			logger.debug("DONE: Computed the new basis.");
		} catch (NotConvergedException e) {
			throw new InvalidArgumentException(e);
		}
		for (Sequence sequence : dataSet.getSequences()) {
			sequence.setPcaVector(pca.sampleToEigenSpace(sequence.getClrVector()));
		}
		logger.debug("DONE: Projected from sample to eigen space.");
	}

	public static void runTsneAndPutResultsToDir(DataSet dataSet, int numThreads, String dir, double theta, double perplexity, int seed, JLabel label_status,
			JProgressBar progBar, File tsneCmd) throws UnhandledOSException, IOException, InterruptedException {

		DataSetFactory.saveToPcaFile(dataSet, dir + "/data.dat", numThreads, theta, perplexity, seed);

		logger.debug("Running command: \"" + tsneCmd + "\" in directory: " + dir + "\n" + "Number of threads: " + numThreads + "\n" + "Seed: " + seed);

		TSNERunner tsne = new TSNERunner(tsneCmd, dir, label_status, progBar);
		Thread tr = new Thread(tsne, "TSNERunner");
		tr.start();
		tr.join();
	}

	static class TSNERunner implements Runnable {
		private File					command;
		private String				dir;
		private JLabel				label_status;
		private JProgressBar	progBar;

		public TSNERunner(File _command, String _dir, JLabel _status, JProgressBar _progBar) {
			command = _command;
			dir = _dir;
			label_status = _status;
			progBar = _progBar;
		}

		public void run() {
			Process process;
			try {
				process = Runtime.getRuntime().exec(new String[] { command.getAbsolutePath() }, new String[] {}, new File(dir));
				/*
				 * Tomasz Sternal - removed this line, it was not updating progress bar
				 * status in mainFrame in real time
				 * 
				 * SwingUtilities.invokeAndWait(new
				 * ProgressReader(process.getInputStream(), label_status, progBar));
				 */
				BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
				String line = reader.readLine();
				Integer newProgress = 0;
				while (line != null) {
					logger.debug("TSNE: " + line);

					// other operations take us to 35% completion, split
					// remaining 65% between TSNE phases
					if (line.startsWith("Building tree"))
						newProgress = 5; // 40%
					if (line.startsWith("Learning embedding"))
						newProgress = 5; // 45%
					if (line.startsWith("Iteration")) { // there are 20
						// iterations
						newProgress = 2; // add 2% progress per iteration
						label_status.setText("T-SNE: " + line.substring(0, line.indexOf(':')) + "/1000");
					}
					if (line.contains("Wrote the"))
						newProgress = 5; // 90%
					progBar.setValue(progBar.getValue() + newProgress);
					line = reader.readLine();
				}
				reader.close();
				process.waitFor();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	/*
	 * Tomasz Sternal - removed this class, not updating window status in real
	 * time
	 */
	// static class ProgressReader implements Runnable {
	// private BufferedReader reader;
	// private JLabel status;
	// private JProgressBar progBar;
	// private Integer newProgress = 0;
	//
	// public ProgressReader(InputStream is, final JLabel _status, final
	// JProgressBar _progBar) {
	// this.reader = new BufferedReader(new InputStreamReader(is));
	// this.status = _status;
	// this.progBar = _progBar;
	// }
	//
	// public void run() {
	// try {
	// String line = reader.readLine();
	// while (line != null) {
	// logger.debug("TSNE: "+line);
	// status.setText("TSNE: "+ line);
	//
	// // other operations take us to 35% completion, split remaining 65%
	// between TSNE phases
	// if (line.startsWith("Building tree")) newProgress = 5; // 40%
	// if (line.startsWith("Learning embedding")) newProgress = 5; // 45%
	// if (line.startsWith("Iteration")) { // there are 20 iterations
	// newProgress = 2; // add 2% progress per iteration
	// }
	// if (line.contains("Wrote the")) newProgress = 5; // 90%
	// progBar.setValue(progBar.getValue()+newProgress);
	// line = reader.readLine();
	// }
	// reader.close();
	// } catch (IOException e) {
	// e.printStackTrace();
	// }
	// }
	// }

	public static boolean isIsDataSetCreated() {
		return isDataSetCreated;
	}

	public static void setIsDataSetCreated(boolean isDataSetCreated) {
		DataSetUtils.isDataSetCreated = isDataSetCreated;
	}

	public static DataSet getDataSet() {
		return dataSet;
	}

	public static void setDataSet(DataSet dataSet) {
		DataSetUtils.dataSet = dataSet;
	}

	// Tomasz Sternal - communication between DataSetUtils and ClusterPannel about
	// pointList
	// and polygonPoints is completely unnecessary
	// public static ArrayList<Sequence> getPointList() {
	// return pointList;
	// }
	//
	// public static void setPointList(ArrayList<Sequence> pointList) {
	// DataSetUtils.pointList = pointList;
	// }
	//
	// public static List<Point2D> getPolygonPoints() {
	// return polygonPoints;
	// }
	//
	// public static void setPolygonPoints(List<Point2D> polygonPoints) {
	// DataSetUtils.polygonPoints = polygonPoints;
	// }

	public static JFrame getDrawingFrame() {
		return drawingFrame;
	}

	public static void setDrawingFrame(JFrame drawingFrame) {
		DataSetUtils.drawingFrame = drawingFrame;
	}

}
