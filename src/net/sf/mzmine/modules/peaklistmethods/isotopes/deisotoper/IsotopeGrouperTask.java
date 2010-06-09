/*
 * Copyright 2006-2010 The MZmine 2 Development Team
 * 
 * This file is part of MZmine 2.
 * 
 * MZmine 2 is free software; you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or (at your option) any later
 * version.
 * 
 * MZmine 2 is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * MZmine 2; if not, write to the Free Software Foundation, Inc., 51 Franklin St,
 * Fifth Floor, Boston, MA 02110-1301 USA
 */

package net.sf.mzmine.modules.peaklistmethods.isotopes.deisotoper;

import java.util.Arrays;
import java.util.Vector;
import java.util.logging.Logger;

import net.sf.mzmine.data.ChromatographicPeak;
import net.sf.mzmine.data.DataPoint;
import net.sf.mzmine.data.IsotopePatternStatus;
import net.sf.mzmine.data.PeakList;
import net.sf.mzmine.data.PeakListAppliedMethod;
import net.sf.mzmine.data.PeakListRow;
import net.sf.mzmine.data.RawDataFile;
import net.sf.mzmine.data.impl.SimpleChromatographicPeak;
import net.sf.mzmine.data.impl.SimpleDataPoint;
import net.sf.mzmine.data.impl.SimpleIsotopePattern;
import net.sf.mzmine.data.impl.SimplePeakList;
import net.sf.mzmine.data.impl.SimplePeakListAppliedMethod;
import net.sf.mzmine.data.impl.SimplePeakListRow;
import net.sf.mzmine.main.MZmineCore;
import net.sf.mzmine.project.MZmineProject;
import net.sf.mzmine.taskcontrol.Task;
import net.sf.mzmine.taskcontrol.TaskStatus;
import net.sf.mzmine.util.PeakSorter;
import net.sf.mzmine.util.PeakUtils;
import net.sf.mzmine.util.SortingDirection;
import net.sf.mzmine.util.SortingProperty;

/**
 * 
 */
class IsotopeGrouperTask implements Task {

	private Logger logger = Logger.getLogger(this.getClass().getName());

	/**
	 * The isotopeDistance constant defines expected distance between isotopes.
	 * Actual weight of 1 neutron is 1.008665 Da, but part of this mass is
	 * consumed as binding energy to other protons/neutrons. Actual mass
	 * increase of isotopes depends on chemical formula of the molecule. Since
	 * we don't know the formula, we can assume the distance to be ~1.0033 Da,
	 * with user-defined tolerance.
	 */
	private static final double isotopeDistance = 1.0033;

	private PeakList peakList, deisotopedPeakList;

	private TaskStatus status = TaskStatus.WAITING;
	private String errorMessage;

	// peaks counter
	private int processedPeaks, totalPeaks;

	// parameter values
	private String suffix;
	private double mzTolerance, rtTolerance;
	private boolean monotonicShape, removeOriginal;
	private int maximumCharge;
	private IsotopeGrouperParameters parameters;

	/**
	 * @param rawDataFile
	 * @param parameters
	 */
	IsotopeGrouperTask(PeakList peakList, IsotopeGrouperParameters parameters) {

		this.peakList = peakList;
		this.parameters = parameters;

		// Get parameter values for easier use
		suffix = (String) parameters
				.getParameterValue(IsotopeGrouperParameters.suffix);
		mzTolerance = (Double) parameters
				.getParameterValue(IsotopeGrouperParameters.mzTolerance);
		rtTolerance = (Double) parameters
				.getParameterValue(IsotopeGrouperParameters.rtTolerance);
		monotonicShape = (Boolean) parameters
				.getParameterValue(IsotopeGrouperParameters.monotonicShape);
		maximumCharge = (Integer) parameters
				.getParameterValue(IsotopeGrouperParameters.maximumCharge);
		removeOriginal = (Boolean) parameters
				.getParameterValue(IsotopeGrouperParameters.autoRemove);

	}

	/**
	 * @see net.sf.mzmine.taskcontrol.Task#getTaskDescription()
	 */
	public String getTaskDescription() {
		return "Isotopic peaks grouper on " + peakList;
	}

	/**
	 * @see net.sf.mzmine.taskcontrol.Task#getFinishedPercentage()
	 */
	public double getFinishedPercentage() {
		if (totalPeaks == 0)
			return 0.0f;
		return (double) processedPeaks / (double) totalPeaks;
	}

	/**
	 * @see net.sf.mzmine.taskcontrol.Task#getStatus()
	 */
	public TaskStatus getStatus() {
		return status;
	}

	/**
	 * @see net.sf.mzmine.taskcontrol.Task#getErrorMessage()
	 */
	public String getErrorMessage() {
		return errorMessage;
	}

	/**
	 * @see net.sf.mzmine.taskcontrol.Task#cancel()
	 */
	public void cancel() {
		status = TaskStatus.CANCELED;
	}

	/**
	 * @see Runnable#run()
	 */
	public void run() {

		status = TaskStatus.PROCESSING;
		logger.info("Running isotopic peak grouper on " + peakList);

		// We assume source peakList contains one datafile
		RawDataFile dataFile = peakList.getRawDataFile(0);

		// Create a new deisotoped peakList
		deisotopedPeakList = new SimplePeakList(peakList + " " + suffix,
				peakList.getRawDataFiles());

		// Collect all selected charge states
		int charges[] = new int[maximumCharge];
		for (int i = 0; i < maximumCharge; i++)
			charges[i] = i + 1;

		// Sort peaks by descending height
		ChromatographicPeak[] sortedPeaks = peakList.getPeaks(dataFile);
		Arrays.sort(sortedPeaks, new PeakSorter(SortingProperty.Height,
				SortingDirection.Descending));

		// Loop through all peaks
		totalPeaks = sortedPeaks.length;

		for (int ind = 0; ind < totalPeaks; ind++) {

			if (status == TaskStatus.CANCELED)
				return;

			ChromatographicPeak aPeak = sortedPeaks[ind];

			// Check if peak was already deleted
			if (aPeak == null) {
				processedPeaks++;
				continue;
			}

			// Check which charge state fits best around this peak
			int bestFitCharge = 0;
			int bestFitScore = -1;
			Vector<ChromatographicPeak> bestFitPeaks = null;
			for (int charge : charges) {

				Vector<ChromatographicPeak> fittedPeaks = new Vector<ChromatographicPeak>();
				fittedPeaks.add(aPeak);
				fitPattern(fittedPeaks, aPeak, charge, sortedPeaks);

				int score = fittedPeaks.size();
				if ((score > bestFitScore)
						|| ((score == bestFitScore) && (bestFitCharge > charge))) {
					bestFitScore = score;
					bestFitCharge = charge;
					bestFitPeaks = fittedPeaks;
				}

			}

			PeakListRow oldRow = peakList.getPeakRow(aPeak);

			// Verify the number of detected isotopes. If there is only one
			// isotope, we skip this left the original peak in the peak list.
			if (bestFitPeaks.size() == 1) {
				deisotopedPeakList.addRow(oldRow);
				processedPeaks++;
				continue;
			}

			DataPoint isotopes[] = new DataPoint[bestFitPeaks.size()];
			for (int i = 0; i < isotopes.length; i++) {
				ChromatographicPeak p = bestFitPeaks.get(i);
				isotopes[i] = new SimpleDataPoint(p.getMZ(), p.getHeight());

			}
			// Assign peaks in best fitted pattern to same isotope pattern
			SimpleIsotopePattern newPattern = new SimpleIsotopePattern(
					isotopes, IsotopePatternStatus.DETECTED, aPeak.toString());

			ChromatographicPeak newPeak = new SimpleChromatographicPeak(aPeak);
			newPeak.setIsotopePattern(newPattern);
			newPeak.setCharge(bestFitCharge);

			// keep old ID

			int oldID = oldRow.getID();
			SimplePeakListRow newRow = new SimplePeakListRow(oldID);
			PeakUtils.copyPeakListRowProperties(oldRow, newRow);
			newRow.addPeak(dataFile, newPeak);
			deisotopedPeakList.addRow(newRow);

			// remove all peaks already assigned to isotope pattern
			for (int i = 0; i < sortedPeaks.length; i++) {
				if (bestFitPeaks.contains(sortedPeaks[i]))
					sortedPeaks[i] = null;
			}

			// Update completion rate
			processedPeaks++;

		}

		// Add new peakList to the project
		MZmineProject currentProject = MZmineCore.getCurrentProject();
		currentProject.addPeakList(deisotopedPeakList);

		// Load previous applied methods
		for (PeakListAppliedMethod proc : peakList.getAppliedMethods()) {
			deisotopedPeakList.addDescriptionOfAppliedTask(proc);
		}

		// Add task description to peakList
		deisotopedPeakList
				.addDescriptionOfAppliedTask(new SimplePeakListAppliedMethod(
						"Isotopic peaks grouper", parameters));

		// Remove the original peakList if requested
		if (removeOriginal)
			currentProject.removePeakList(peakList);

		logger.info("Finished isotopic peak grouper on " + peakList);
		status = TaskStatus.FINISHED;

	}

	/**
	 * Fits isotope pattern around one peak.
	 * 
	 * @param p
	 *            Pattern is fitted around this peak
	 * @param charge
	 *            Charge state of the fitted pattern
	 */
	private void fitPattern(Vector<ChromatographicPeak> fittedPeaks,
			ChromatographicPeak p, int charge, ChromatographicPeak[] sortedPeaks) {

		if (charge == 0) {
			return;
		}

		// Search for peaks before the start peak
		if (!monotonicShape) {
			fitHalfPattern(p, charge, -1, fittedPeaks, sortedPeaks);
		}

		// Search for peaks after the start peak
		fitHalfPattern(p, charge, 1, fittedPeaks, sortedPeaks);

	}

	/**
	 * Helper method for fitPattern. Fits only one half of the pattern.
	 * 
	 * @param p
	 *            Pattern is fitted around this peak
	 * @param charge
	 *            Charge state of the fitted pattern
	 * @param direction
	 *            Defines which half to fit: -1=fit to peaks before start M/Z,
	 *            +1=fit to peaks after start M/Z
	 * @param fittedPeaks
	 *            All matching peaks will be added to this set
	 */
	private void fitHalfPattern(ChromatographicPeak p, int charge,
			int direction, Vector<ChromatographicPeak> fittedPeaks,
			ChromatographicPeak[] sortedPeaks) {

		// Use M/Z and RT of the strongest peak of the pattern (peak 'p')
		double mainMZ = p.getMZ();
		double mainRT = p.getRT();

		// Variable n is the number of peak we are currently searching. 1=first
		// peak before/after start peak, 2=peak before/after previous, 3=...
		boolean followingPeakFound;
		int n = 1;
		do {

			// Assume we don't find match for n:th peak in the pattern (which
			// will end the loop)
			followingPeakFound = false;

			// Loop through all peaks, and collect candidates for the n:th peak
			// in the pattern
			Vector<ChromatographicPeak> goodCandidates = new Vector<ChromatographicPeak>();
			for (int ind = 0; ind < sortedPeaks.length; ind++) {

				ChromatographicPeak candidatePeak = sortedPeaks[ind];

				if (candidatePeak == null)
					continue;

				// Get properties of the candidate peak
				double candidatePeakMZ = candidatePeak.getMZ();
				double candidatePeakRT = candidatePeak.getRT();

				// Does this peak fill all requirements of a candidate?
				// - within tolerances from the expected location (M/Z and RT)
				// - not already a fitted peak (only necessary to avoid
				// conflicts when parameters are set too wide)

				if ((Math.abs((candidatePeakMZ - isotopeDistance * direction
						* n / (double) charge)
						- mainMZ) <= mzTolerance)
						&& (Math.abs(candidatePeakRT - mainRT) < rtTolerance)
						&& (!fittedPeaks.contains(candidatePeak))) {
					goodCandidates.add(candidatePeak);

				}

			}

			// If there are some candidates for n:th peak, then select the one
			// with biggest intensity
			// We collect all candidates, because we might want to do something
			// more sophisticated at this step. For example, we might want to
			// remove all other candidates. However, currently nothing is done
			// with other candidates.
			ChromatographicPeak bestCandidate = null;
			for (ChromatographicPeak candidatePeak : goodCandidates) {
				if (bestCandidate != null) {
					if (bestCandidate.getHeight() < candidatePeak.getHeight()) {
						bestCandidate = candidatePeak;
					}
				} else {
					bestCandidate = candidatePeak;
				}

			}

			// If best candidate was found, then assign it to this isotope
			// pattern
			if (bestCandidate != null) {

				// Add best candidate to fitted peaks of the pattern
				fittedPeaks.add(bestCandidate);

				// n:th peak was found, so let's move on to n+1
				n++;
				followingPeakFound = true;

			}

		} while (followingPeakFound);

	}

	public Object[] getCreatedObjects() {
		return new Object[] { deisotopedPeakList };
	}

}