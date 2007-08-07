/*
 * Copyright 1997-2007 Unidata Program Center/University Corporation for
 * Atmospheric Research, P.O. Box 3000, Boulder, CO 80307,
 * support@unidata.ucar.edu.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 2.1 of the License, or (at
 * your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; if not, write to the Free Software Foundation,
 * Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */

package ucar.nc2.dt.fmrc;

import org.jdom.output.XMLOutputter;
import org.jdom.output.Format;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.input.SAXBuilder;

import java.io.*;
import java.util.*;

import ucar.nc2.dataset.CoordinateAxis1D;
import ucar.unidata.util.StringUtil;

/**
 * Defines the expected inventory of a Forecast Model Run Collection.
 *
 * <pre>
 * Data Structures
 *
 *  List VertTimeCoord
 *    double[] values
 *
 *  List TimeCoord
 *    double[] offsetHour
 *
 *  List RunSeq
 *    List Run - runHour dependent TimeCoord
 *      double hour
 *      TimeCoord
 *
 *    List Grid
 *     String name
 *     VertTimeCoord - time dependent vertical coordinate
 *       VertCoord
 *       TimeCoord (optional)
 *
 *
 * Abstractly, the data is a table:
 *   Run  Grid  TimeCoord  VertCoord
 *   Run  Grid  TimeCoord  VertCoord
 *   Run  Grid  TimeCoord  VertCoord
 *   ...
 *
 * We will use the notation ({} means list)
 *  {Run, Grid, TimeCoord, VertCoord}
 *
 * The simplest case would be if all runs have the same grids, which all use the same time coord, and each grid always
 *  uses the same vert coord :
 * (1) {runTime} X {Grid, VertCoord} X TimeCoord      (X means product)
 *
 * The usual case is that there are multiple TimeCoords, but a grid always uses the same one:
 * (2) {runTime} X {Grid, VertCoord, TimeCoord}
 *
 * Since all runTimes are the same, the definition only need be:
 * (2d) {Grid, VertCoord, TimeCoord}

 * Another case is that different run hours use different TimeCoords. We will call this a RunSeq, and we associate with each
 * RunSeq the list of grids that use it:
 *   Run = runHour, TimeCoord
 *   RunSeq = {runHour, TimeCoord} X {Grid, VertCoord}
 *
 * Different grids use different RunSeqs, so we have a list of RunSeq:
 * (3d) {{runHour, TimeCoord} X {Grid, VertCoord}}
 *
 * We can recast (2d), when all runHours are the same,  as:
 * (2d') {TimeCoord X {Grid, VertCoord}}
 * which means that we are grouping grids by unique TimeCoord. (1d) would be the case where there is only one in the list.
 *
 * Another case is when the VertCoord depends on the TimeCoord, but all run hours are the same:
 * (4d) {TimeCoord X {Grid, VertCoord(TimeCoord)}}
 *
 * Which lead us to generalize a VertCoord to a time-dependent one, called VertTimeCoord.
 *
 * The most general case is then
 *   {{runHour, TimeCoord} X {Grid, VertTimeCoord}}
 *
 * </pre>
 *
 *
 * @author caron
 */
public class FmrcDefinition implements ucar.nc2.dt.fmr.FmrcCoordSys {
  static private org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FmrcDefinition.class);

  ///////////////////////////////////////////////////////////////////

  private List<VertTimeCoord> vertTimeCoords;
  private List<ForecastModelRunInventory.TimeCoord> timeCoords;
  private List<RunSeq> runSequences;

  private String name;
  private String suffixFilter;

  public FmrcDefinition() {
    cal.setTimeZone( TimeZone.getTimeZone("UTC"));
  }

  public String getSuffixFilter() { return suffixFilter; }

  public List<RunSeq> getRunSequences() { return runSequences; }

  public boolean hasVariable(String searchName) {
    return findGridByName( searchName) != null;
  }

  public ucar.nc2.dt.fmr.FmrcCoordSys.VertCoord findVertCoordForVariable(String searchName) {
    Grid grid = findGridByName( searchName);
    return (grid.vtc == null) ? null : grid.vtc.vc;
  }

  public ucar.nc2.dt.fmr.FmrcCoordSys.TimeCoord findTimeCoordForVariable(String searchName, java.util.Date runTime) {
    for (RunSeq runSeq : runSequences) {
      Grid grid = runSeq.findGrid(searchName);
      if (null != grid) {
        ForecastModelRunInventory.TimeCoord from = runSeq.findTimeCoordByRuntime(runTime);
        // we need to wrap it, giving it the name of the RunSeq
        return new ForecastModelRunInventory.TimeCoord(runSeq.num, from);
      }
    }
    return null;
  }

  private ForecastModelRunInventory.TimeCoord findTimeCoord( String id) {
    for (ForecastModelRunInventory.TimeCoord tc : timeCoords) {
      if (tc.getId().equals(id))
        return tc;
    }
    return null;
  }

  ///////////////////////////////////////////////////////////////////////////////////////////////

  // encapsolates a Grid and its vertical coordinate, which may be time-dependent
  public static class Grid implements Comparable {
    private String name; // , searchName;
    private VertTimeCoord vtc = null;

    Grid (String name) {
      this.name = name;
    }

    //public String getName() { return name; }
    //public void setName(String name) { this.name = name; }
    public VertTimeCoord getVertTimeCoord() { return vtc; }

    public int countVertCoords( double offsetHour) {
      return (vtc == null) ? 1 : vtc.countVertCoords( offsetHour);
    }

    /**
     * Get inventory as an array of vert coords, at a particular time coord = hourOffset
     *
     * @param hourOffset : may or may not be in the list of time coords
     * @return array of vert coords. NaN = missing; -0.0 = surface.
     */
    public double[] getVertCoords(double hourOffset) {

      if (vtc == null) {
        double[] result = new double[1]; // if 2D return -0.0
        result[0] = -0.0;
        return result;
      }

      return vtc.getVertCoords(hourOffset);
    }

    public int compareTo(Object o) {
      Grid other = (Grid) o;
      return name.compareTo(other.name);
    }
  }

  class Run {
    double runHour; // hour since 00:00
    ForecastModelRunInventory.TimeCoord tc;

    Run(ForecastModelRunInventory.TimeCoord tc, double runHour) {
      this.tc = tc;
      this.runHour = runHour;
    }
  }

  // A sequence of Runs.
  // each RunSeq is a unique time coordinate
  private int runseq_num = 0;

  public class RunSeq {
    private boolean isAll = false;    // true if they all use the same OffsetHours
    private ForecastModelRunInventory.TimeCoord allUseOffset; // they all use this one
    private List<Run> runs = new ArrayList<Run>(); // list of Run
    private List<Grid> vars = new ArrayList<Grid>(); // list of Grid
    private int num = 0;

    RunSeq(String id) {
      this.isAll = true;
      this.allUseOffset = findTimeCoord( id);
      num = runseq_num++;
    }

    RunSeq(List<Run> runs) {
      this.runs = runs;
      num = runseq_num++;

      // complete a 24 hour cycle
      int matchIndex = 0;
      Run last = runs.get(runs.size()-1);
      double runHour = last.runHour;
      while (runHour < 24.0) {
        Run match = runs.get(matchIndex);
        Run next = runs.get(matchIndex+1);
        double incr = next.runHour - match.runHour;
        if (incr <= 0)
          break;
        runHour += incr;
        runs.add( new Run(next.tc, runHour));
        matchIndex++;
      }
    }

    public String getName() { return (num == 0) ? "time" : "time"+num; }

    /**
     * Find the TimeCoord the should be used for this runTime
     * @param runTime  run date
     * @return TimeCoord, or null if no match.
     */
    public ForecastModelRunInventory.TimeCoord findTimeCoordByRuntime( Date runTime) {
      if (isAll)
        return allUseOffset;
      double hour = getHour( runTime);
      Run run = findRun( hour);
      if (run == null) return null;
      return run.tc;
    }

    /** @return the Run that matches the run hour
     * @param hour run hour
     */
    Run findRun( double hour) {
      for (Run run : runs) {
        if (run.runHour == hour) return run;
      }
      return null;
    }

    Grid findGrid( String name) {
      if (name == null) return null;

      for (Grid grid : vars) {
        if (name.equals(grid.name))
          return grid;
      }

      return null;
    }

  } // RunSeq

  RunSeq findSeqForVariable( String name) {
    for (RunSeq runSeq : runSequences) {
      if (runSeq.findGrid(name) != null)
        return runSeq;
    }
    return null;
  }

  Grid findGridByName( String name) {
    for (RunSeq runSeq : runSequences) {
      Grid grid = runSeq.findGrid(name);
      if (null != grid)
        return grid;
    }
    return null;
  }

  VertTimeCoord findVertCoord( String id) {
     if (id == null)
       return null;

    for (VertTimeCoord vc : vertTimeCoords) {
      if (vc.getId().equals(id))
        return vc;
    }
    return null;
   }

  VertTimeCoord findVertCoordByName( String name) {
    for (VertTimeCoord vc : vertTimeCoords) {
      if (vc.getName().equals(name))
        return vc;
    }
    return null;
   }

  boolean replaceVertCoord( ForecastModelRunInventory.VertCoord vc) {
    for (VertTimeCoord vtc : vertTimeCoords) {
      if (vtc.getName().equals(vc.getName())) {
        vtc.vc.values1 = vc.values1;
        vtc.vc.values2 = vc.values2;
        vtc.vc.setId(vc.getId());
        vtc.vc.setUnits(vc.getUnits());
        return true;
      }
    }

    // make a new one
     vertTimeCoords.add( new VertTimeCoord(vc));
     return false;
   }


  ///////////////////////////////////////////////////////////////////////////////////////////////

  /* vertical coordinates that depend on the offset hour */
  class VertTimeCoord implements Comparable {
    ForecastModelRunInventory.VertCoord vc;
    ForecastModelRunInventory.TimeCoord tc; // optional
    int ntimes, nverts;
    double[][] vcForTimeIndex;  // vcForTimeIndex[ntimes]
    List<String> restrictList;

    VertTimeCoord(ForecastModelRunInventory.VertCoord vc) {
      this.vc = vc;
      ntimes = (tc == null) ? 1 : tc.getOffsetHours().length;
      nverts = vc.getValues1().length;
    }

    VertTimeCoord(ForecastModelRunInventory.VertCoord vc, RunSeq runSeq) {
      if (runSeq.isAll) {
        this.tc = runSeq.allUseOffset;
      } else {
        // make union timeCoord
        Set<Double> valueSet = new HashSet<Double>();
        for (Run run : runSeq.runs) {
          addValues(valueSet, run.tc.getOffsetHours());
        }
        List<Double> valueList = Arrays.asList( (Double[]) valueSet.toArray());
        Collections.sort( valueList);
        double[] values = new double[valueList.size()];
        for (int i = 0; i < valueList.size(); i++) {
          values[i] = valueList.get(i);
        }
        this.tc = new ForecastModelRunInventory.TimeCoord();
        this.tc.setOffsetHours( values);
        this.tc.setId( "union");
      }

      this.vc = vc;

      ntimes = (tc == null) ? 1 : tc.getOffsetHours().length;
      nverts = vc.getValues1().length;
    }

    private void addValues(Set<Double> valueSet, double[] values) {
      for (double value : values) valueSet.add(value);
    }

    String getId() { return vc.getId(); }
    String getName() { return vc.getName(); }
    //double[] getValues() { return vc.getValues(); }

    void addRestriction(String vertCoordsString, String timeCoords) {
      StringTokenizer stoker = new StringTokenizer( vertCoordsString, " ,");
      int n = stoker.countTokens();
      double[] vertCoords = new double[n];
      int count = 0;
      while (stoker.hasMoreTokens()) {
        vertCoords[count++] = Double.parseDouble(stoker.nextToken());
      }

      if (vcForTimeIndex == null) {
        restrictList = new ArrayList<String>();
        vcForTimeIndex = new double[ntimes][];
        for (int i = 0; i < vcForTimeIndex.length; i++) {
          vcForTimeIndex[i] = vc.getValues1(); // LOOK WRONG
        }
      }

      // save these in case we have to write them back out
      restrictList.add( vertCoordsString);
      restrictList.add( timeCoords);

      stoker = new StringTokenizer(timeCoords, " ,");
      while (stoker.hasMoreTokens()) {
        double hour = Double.parseDouble(stoker.nextToken());
        int index = tc.findIndex(hour);
        if (index < 0)
          log.error("hour Offset"+hour+" not found in TimeCoord "+tc.getId());
        vcForTimeIndex[index] = vertCoords;
      }
    }

    double[] getVertCoords( double offsetHour) {
      if ((tc == null) || (null == vcForTimeIndex))
        return vc.getValues1(); // LOOK WRONG

      int index = tc.findIndex(offsetHour);
      if (index < 0)
        return new double[0];

      return vcForTimeIndex[index];
    }

    int countVertCoords( double offsetHour) {
      if ((tc == null) || (null == vcForTimeIndex))
        return vc.getValues1().length;

      int index = tc.findIndex(offsetHour);
      if (index < 0)
        return 0;

      return vcForTimeIndex[index].length;
    }

    public int compareTo(Object o) {
      VertTimeCoord other = (VertTimeCoord) o;
      return getName().compareTo( other.getName());
    }
  }


  //////////////////////////////////////////////////////////////////////////////////

  public String writeDefinitionXML() {
    XMLOutputter fmt = new XMLOutputter(Format.getPrettyFormat());
    return fmt.outputString(makeDefinitionXML());
  }

  public void writeDefinitionXML(OutputStream os) throws IOException {
    XMLOutputter fmt = new XMLOutputter(Format.getPrettyFormat());
    fmt.output(makeDefinitionXML(), os);
  }

  /**
   * Create an XML document for the entire collection
   * @return an XML document for the entire collection
   */
  public Document makeDefinitionXML() {
    Element rootElem = new Element("fmrcDefinition");
    Document doc = new Document(rootElem);
    if (name != null)
      rootElem.setAttribute("dataset", name);
    if (null != suffixFilter)
      rootElem.setAttribute("suffixFilter", suffixFilter);

    // list all the vertical coordinaates
    for (VertTimeCoord vtc : vertTimeCoords) {
      ForecastModelRunInventory.VertCoord vc = vtc.vc;

      Element vcElem = new Element("vertCoord");
      rootElem.addContent(vcElem);
      vcElem.setAttribute("id", vc.getId());
      vcElem.setAttribute("name", vc.getName());
      if (null != vc.getUnits())
        vcElem.setAttribute("units", vc.getUnits());

      StringBuffer sbuff = new StringBuffer();
      double[] values1 = vc.getValues1();
      double[] values2 = vc.getValues2();

      for (int j = 0; j < values1.length; j++) {
        if (j > 0) sbuff.append(" ");
        sbuff.append(Double.toString(values1[j]));
        if (values2 != null) {
          sbuff.append(",");
          sbuff.append(Double.toString(values2[j]));
        }
      }
      vcElem.addContent(sbuff.toString());
    }

    // list all the offset hours
    for (ForecastModelRunInventory.TimeCoord tc : timeCoords) {
      Element offsetElem = new Element("offsetHours");
      rootElem.addContent(offsetElem);
      offsetElem.setAttribute("id", tc.getId());

      StringBuffer sbuff = new StringBuffer();
      double[] offset = tc.getOffsetHours();
      for (int j = 0; j < offset.length; j++) {
        if (j > 0) sbuff.append(" ");
        sbuff.append(Double.toString(offset[j]));
      }
      offsetElem.addContent(sbuff.toString());
    }

    // list all the time sequences, containing variables
    for (RunSeq runSeq : runSequences) {
      Element seqElem = new Element("runSequence");
      rootElem.addContent(seqElem);

      if (runSeq.isAll) {
        seqElem.setAttribute("allUseSeq", runSeq.allUseOffset.getId());

      } else { // otherwise show each run
        for (Run run : runSeq.runs) {
          Element runElem = new Element("run");
          seqElem.addContent(runElem);

          runElem.setAttribute("runHour", Double.toString(run.runHour));
          runElem.setAttribute("offsetHourSeq", run.tc.getId());
        }
      }

      for (Grid grid : runSeq.vars) {
        Element varElem = new Element("variable");
        seqElem.addContent(varElem);
        varElem.setAttribute("name", grid.name);
        if (grid.vtc != null) {
          varElem.setAttribute("vertCoord", grid.vtc.getId());

          // look for time-dependent vert coordinates - always specific to one variable
          if (grid.vtc.restrictList != null) {
            Iterator iter = grid.vtc.restrictList.iterator();
            while (iter.hasNext()) {
              Element vtElem = new Element("vertCoord");
              varElem.addContent(vtElem);
              vtElem.setAttribute("restrict", (String) iter.next());
              vtElem.setText((String) iter.next());
            }
          }
        }
      }
    }
    return doc;
  }

  public boolean readDefinitionXML(String xmlLocation) throws IOException {
    File xml = new File(xmlLocation);
    if (!xml.exists()) return false;

    InputStream is = new BufferedInputStream( new FileInputStream( xmlLocation));
    org.jdom.Document doc;
    try {
      SAXBuilder builder = new SAXBuilder();
      doc = builder.build(is);
    } catch (JDOMException e) {
      throw new IOException(e.getMessage());
    }

    Element rootElem = doc.getRootElement();
    name = rootElem.getAttributeValue("name");
    suffixFilter = rootElem.getAttributeValue("suffixFilter");

    vertTimeCoords = new ArrayList<VertTimeCoord>();
    java.util.List<Element> vList = rootElem.getChildren("vertCoord");
    for (Element vcElem : vList) {
      ForecastModelRunInventory.VertCoord vc = new ForecastModelRunInventory.VertCoord();
      vc.setId(vcElem.getAttributeValue("id"));
      vc.setName(vcElem.getAttributeValue("name"));
      vc.setUnits(vcElem.getAttributeValue("units"));

      /* parse the values
      String values = vcElem.getText();
      StringTokenizer stoke = new StringTokenizer(values);
      int n = stoke.countTokens();
      double[] vals = new double[n];
      int count = 0;
      while (stoke.hasMoreTokens()) {
        vals[count++] = Double.parseDouble( stoke.nextToken());
      }
      vc.setValues( vals); */

      // parse the values
      String values = vcElem.getText();
      StringTokenizer stoke = new StringTokenizer(values);
      int n = stoke.countTokens();
      double[] values1 = new double[n];
      double[] values2 = null;
      int count = 0;
      while (stoke.hasMoreTokens()) {
        String toke = stoke.nextToken();
        int pos = toke.indexOf(',');
        if (pos < 0)
          values1[count] = Double.parseDouble(toke);
        else {
          if (values2 == null)
            values2 = new double[n];
          String val1 = toke.substring(0, pos);
          String val2 = toke.substring(pos + 1);
          values1[count] = Double.parseDouble(val1);
          values2[count] = Double.parseDouble(val2);
        }
        count++;
      }
      vc.setValues1(values1);
      if (values2 != null)
        vc.setValues2(values2);

      // wrap it as a VertTimeCoord
      VertTimeCoord vtc = new VertTimeCoord(vc);
      vertTimeCoords.add(vtc);
    }

    timeCoords = new ArrayList<ForecastModelRunInventory.TimeCoord>();
    java.util.List<Element> tList = rootElem.getChildren("offsetHours");
    for (Element timeElem : tList) {
      ForecastModelRunInventory.TimeCoord tc = new ForecastModelRunInventory.TimeCoord();
      timeCoords.add(tc);
      tc.setId(timeElem.getAttributeValue("id"));

      // parse the values
      String values = timeElem.getText();
      StringTokenizer stoke = new StringTokenizer(values);
      int n = stoke.countTokens();
      double[] offset = new double[n];
      int count = 0;
      while (stoke.hasMoreTokens()) {
        offset[count++] = Double.parseDouble(stoke.nextToken());
      }
      tc.setOffsetHours(offset);
    }

    runSequences = new ArrayList<RunSeq>();
    java.util.List<Element> runseqList = rootElem.getChildren("runSequence");
    for (Element runseqElem : runseqList) {
      RunSeq rseq;

      String allUseId = runseqElem.getAttributeValue("allUseSeq");
      if (allUseId != null) {
        rseq = new RunSeq(allUseId);
      } else {
        List<Run> runs = new ArrayList<Run>();
        List<Element> runList = runseqElem.getChildren("run");
        for (Element runElem : runList) {
          String id = runElem.getAttributeValue("offsetHourSeq");
          ForecastModelRunInventory.TimeCoord tc = findTimeCoord(id);
          String hour = runElem.getAttributeValue("runHour");

          Run run = new Run(tc, Double.parseDouble(hour));
          runs.add(run);
        }
        rseq = new RunSeq(runs);
      }

      runSequences.add(rseq);

      List<Element> varList = runseqElem.getChildren("variable");
      for (Element varElem : varList) {
        String name = varElem.getAttributeValue("name");
        Grid grid = new Grid(name);
        rseq.vars.add(grid);
        grid.vtc = findVertCoord(varElem.getAttributeValue("vertCoord"));

        // look for time dependent vert coordinates - always specific to one variable
        List<Element> rList = varElem.getChildren("vertTimeCoord");
        // gotta be inside a useAllSeq runSeq
        if (rList.size() > 0) {
          grid.vtc = new VertTimeCoord(grid.vtc.vc, rseq);

          for (Element vtElem : rList) {
            String vertCoords = vtElem.getAttributeValue("restrict");
            String timeCoords = vtElem.getText();
            grid.vtc.addRestriction(vertCoords, timeCoords);
          }
        }
      }
      Collections.sort(rseq.vars);

    }

    return true;
  }

  //////////////////////////////////////////////////////////////////////

  public void makeFromCollectionInventory(FmrcInventory fmrc) {
    this.name = fmrc.getName();

    this.timeCoords = fmrc.getTimeCoords();

    this.vertTimeCoords = new ArrayList<VertTimeCoord>();
    for (int i = 0; i < fmrc.getVertCoords().size(); i++) {
      ForecastModelRunInventory.VertCoord vc = fmrc.getVertCoords().get(i);
      vertTimeCoords.add( new VertTimeCoord(vc));
    }

    this.runSequences = new ArrayList<RunSeq>();

    // Convert the run sequences, containing variables
    List<FmrcInventory.RunSeq> seqs = fmrc.getRunSequences();
    for (FmrcInventory.RunSeq invSeq : seqs) {
      // check to see if all runs use the same tc
      boolean isAll = true;
      ForecastModelRunInventory.TimeCoord oneTc = null;
      for (int j = 0; j < invSeq.runs.size(); j++) {
        FmrcInventory.Run run = invSeq.runs.get(j);
        if (j == 0)
          oneTc = run.tc;
        else {
          if (oneTc != run.tc)
            isAll = false;
        }
      }

      RunSeq runSeq;
      if (isAll) {
        runSeq = new RunSeq(oneTc.getId());
      } else {
        List<Run> runs = new ArrayList<Run>();
        for (FmrcInventory.Run invRun : invSeq.runs) {
          Run run = new Run(invRun.tc, getHour(invRun.runTime));
          runs.add(run);
        }
        runSeq = new RunSeq(runs);
      }
      runSequences.add(runSeq);

      // convert UberGrids to Grid
      List<FmrcInventory.UberGrid> vars = invSeq.getVariables();
      for (FmrcInventory.UberGrid uv : vars) {
        Grid grid = new Grid(uv.getName());
        runSeq.vars.add(grid);
        if (uv.vertCoordUnion != null)
          grid.vtc = new VertTimeCoord(uv.vertCoordUnion);
      }
    }

    // sort vert coords
    Collections.sort(this.vertTimeCoords);

  }

  /** Add just the vertical coord info to the definition
   * @param fmrc the collection inventory
   */
  public void addVertCoordsFromCollectionInventory(FmrcInventory fmrc) {
    this.vertTimeCoords = new ArrayList<VertTimeCoord>();
    for (int i = 0; i < fmrc.getVertCoords().size(); i++) {
      ForecastModelRunInventory.VertCoord vc = fmrc.getVertCoords().get(i);
      vertTimeCoords.add( new VertTimeCoord(vc));
    }

    // Convert the run sequences, containing variables
    List<FmrcInventory.RunSeq> seqs = fmrc.getRunSequences();
    for (FmrcInventory.RunSeq invSeq : seqs) {
      // convert UberGrids to Grid
      List<FmrcInventory.UberGrid> vars = invSeq.getVariables();
      for (FmrcInventory.UberGrid uv : vars) {
        if (uv.vertCoordUnion != null) {
          String sname = uv.getName();
          Grid grid = findGridByName(sname);
          grid.vtc = new VertTimeCoord(uv.vertCoordUnion);
        }
      }
    }
  }

    //  utilities - move to fmrc ?
  private Calendar cal = new GregorianCalendar(); // for date computations

  private double getHour( Date d) {
    cal.setTime( d);
    int hour = cal.get(Calendar.HOUR_OF_DAY);
    double min = (double) cal.get(Calendar.MINUTE);
    return hour + min/60;
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////////
  // ad-hoc manipulations of the definitions

  // replace ids
  static void convertIds(String datasetName, String defName) throws IOException {
    System.out.println(datasetName);
    ForecastModelRunInventory fmrInv = ForecastModelRunInventory.open(null, datasetName, ForecastModelRunInventory.OPEN_FORCE_NEW, true);

    FmrcDefinition fmrDef = new FmrcDefinition();
    fmrDef.readDefinitionXML(defName);

    boolean changed = false;

    // make hashmap
    Map<String,Grid> hash = new HashMap<String,Grid>();
    for (RunSeq runSeq : fmrDef.runSequences) {
      for (Grid gridDef : runSeq.vars) {
        String munged = StringUtil.replace(gridDef.name, '_', "");
        hash.put(munged, gridDef);
      }
    }

    // make sure each grid in fmrInv is also in fmrDef
    List<ForecastModelRunInventory.TimeCoord> fmrInvTimeCoords = fmrInv.getTimeCoords();
    for (ForecastModelRunInventory.TimeCoord tc : fmrInvTimeCoords) {
      List<ForecastModelRunInventory.Grid> fmrInvGrids = tc.getGrids();
      for (ForecastModelRunInventory.Grid invGrid : fmrInvGrids) {
        Grid defGrid = fmrDef.findGridByName(invGrid.name);
        if (null == defGrid) {
          String munged = StringUtil.replace(invGrid.name, "-", "");
          munged = StringUtil.replace(munged, "_", "");
          Grid gridDefnew = hash.get(munged);
          if (gridDefnew != null) {
            System.out.println(" replace " + gridDefnew.name + " with " + invGrid.name);
            gridDefnew.name = invGrid.name;
            changed = false; // true;
          } else {
            System.out.println("*** cant find replacement for grid= " + invGrid.name + " in the definition");
          }
        }
      }
    }

    if (changed) {
      int pos = defName.lastIndexOf("/");
      String newDef = defName.substring(0,pos) + "/new/" + defName.substring(pos);
      FileOutputStream fout = new FileOutputStream( newDef);
      fmrDef.writeDefinitionXML( fout);
    }
  }

  static boolean showState = false;
  static void convert(String datasetName, String defName) throws IOException {
    System.out.println(datasetName);
    ForecastModelRunInventory fmrInv = ForecastModelRunInventory.open(null, datasetName, ForecastModelRunInventory.OPEN_FORCE_NEW, true);

    FmrcDefinition fmrDef = new FmrcDefinition();
    fmrDef.readDefinitionXML(defName);

    // replace vert coords
    List<ForecastModelRunInventory.VertCoord> vcList = fmrInv.getVertCoords();
    for (ForecastModelRunInventory.VertCoord vc : vcList) {
      CoordinateAxis1D axis = vc.axis;
      if (axis == null)
        System.out.println("*** No Axis " + vc.getName());
      else {
        // boolean isLayer = null != axis.findAttribute(_Coordinate.ZisLayer);
        //if (isLayer) {
        if (showState) System.out.print(" " + vc.getName() + " contig= " + axis.isContiguous());
        // see if theres any TimeCoord that use this
        //findGridsForVertCoord( fmrDef, vc);
        boolean ok = fmrDef.replaceVertCoord(vc);
        if (showState) System.out.println(" = " + ok);
        //}
      }
    }
    Collections.sort( fmrDef.vertTimeCoords);

    // reset vert id on grids
    for (RunSeq runSeq : fmrDef.runSequences) {
      for (Grid gridDef : runSeq.vars) {
        ForecastModelRunInventory.Grid gridInv = fmrInv.findGrid(gridDef.name);
        if (gridInv == null) {
          System.out.println("*** cant find def grid= " + gridDef.name + " in the inventory ");
          continue;
        }
        if (gridInv.vc != null) {
          VertTimeCoord new_vtc = fmrDef.findVertCoordByName(gridInv.vc.getName());
          if (new_vtc == null) {
            System.out.println("*** cant find VertCoord= " + gridInv.vc.getName());
            continue;
          }
          gridDef.vtc = new_vtc;
          if (showState) System.out.println(" ok= " + gridDef.name);
        }
      }
    }

    int pos = defName.lastIndexOf("/");
    String newDef = defName.substring(0,pos) + "/new/" + defName.substring(pos);
    FileOutputStream fout = new FileOutputStream( newDef);
    fmrDef.writeDefinitionXML( fout);

            // make sure each grid in fmrInv is also in fmrDef
    List<ForecastModelRunInventory.TimeCoord> fmrInvTimeCoords = fmrInv.getTimeCoords();
    for (ForecastModelRunInventory.TimeCoord tc : fmrInvTimeCoords) {
      List<ForecastModelRunInventory.Grid> fmrInvGrids = tc.getGrids();
      for (ForecastModelRunInventory.Grid invGrid : fmrInvGrids) {
        Grid defGrid = fmrDef.findGridByName(invGrid.name);
        if (null == defGrid)
          System.out.println("*** cant find inv grid= " + invGrid.name + " in the definition");
        else {
          ForecastModelRunInventory.VertCoord inv_vc = invGrid.vc;

          if ((inv_vc == null) && (defGrid.vtc == null)) continue; // ok
          if ((inv_vc != null) && (defGrid.vtc == null)) {
            System.out.println("*** mismatch " + invGrid.name + " VertCoord: inv= " + inv_vc.getSize() + ", no def ");
            continue;
          }

          if ((inv_vc == null) && (defGrid.vtc != null)) {
            ForecastModelRunInventory.VertCoord def_vc = defGrid.vtc.vc;
            System.out.println("*** mismatch " + invGrid.name + " VertCoord: def= " + def_vc.getSize() + ", no inv ");
            continue;
          }

          ForecastModelRunInventory.VertCoord def_vc = defGrid.vtc.vc;
          if (inv_vc.getSize() != def_vc.getSize()) {
            System.out.println("*** mismatch " + invGrid.name + " VertCoord size: inv= " + inv_vc.getSize() + ", def = " + def_vc.getSize());
          }
        }
      }

    }
  }

  static void showVertCoords(String datasetName, String defName ) throws IOException {
    System.out.println("--------------------------------------");
    System.out.println(defName);
    FmrcDefinition fmrDef = new FmrcDefinition();
    fmrDef.readDefinitionXML(defName);

    /* List vtList = fmrDef.vertTimeCoords;
    for (int i = 0; i < vtList.size(); i++) {
      VertTimeCoord vtc = (VertTimeCoord) vtList.get(i);
      System.out.println(" "+vtc.getName());
    } */

    System.out.println(datasetName);
    ForecastModelRunInventory fmrInv = ForecastModelRunInventory.open(null, datasetName, ForecastModelRunInventory.OPEN_FORCE_NEW, true);
    List<ForecastModelRunInventory.VertCoord> vcList = fmrInv.getVertCoords();
    for (ForecastModelRunInventory.VertCoord vc : vcList) {
      CoordinateAxis1D axis = vc.axis;
      if (axis == null)
        System.out.println(" No Axis " + vc.getName());
      else {
        if (axis.isLayer()) {
          System.out.println(" Layer " + vc.getName() + " contig= " + axis.isContiguous());
          // see if theres any TimeCoord that use this
          findGridsForVertCoord(fmrDef, vc);
        }
      }
      boolean got = fmrDef.findVertCoordByName(vc.getName()) != null;
      if (!got)
        System.out.println(" ***NOT " + vc.getName());
    }
  }

  static void findGridsForVertCoord(FmrcDefinition fmrDef, ForecastModelRunInventory.VertCoord vc) {
    for (RunSeq runSeq : fmrDef.runSequences) {
      for (Grid grid : runSeq.vars) {
        if ((grid.vtc != null) && (grid.vtc.vc == vc)) {
          List restrictList = grid.vtc.restrictList;
          if (restrictList != null && restrictList.size() > 0)
            System.out.println(" TimeVertCoord refers to this vertical coordinate");
        }
      }
    }
  }

  public static String[] fmrcDefinitionFiles = {
   "R:/testdata/motherlode/grid/modelDefs/NCEP-GFS-Alaska_191km.fmrcDefinition.xml",
   "R:/testdata/motherlode/grid/modelDefs/NCEP-GFS-CONUS_80km.fmrcDefinition.xml",
   "R:/testdata/motherlode/grid/modelDefs/NCEP-GFS-CONUS_191km.fmrcDefinition.xml",
   "R:/testdata/motherlode/grid/modelDefs/NCEP-GFS-CONUS_95km.fmrcDefinition.xml", // */
   "R:/testdata/motherlode/grid/modelDefs/NCEP-GFS-Global_2p5deg.fmrcDefinition.xml",
   "R:/testdata/motherlode/grid/modelDefs/NCEP-GFS-Global_onedeg.fmrcDefinition.xml",
   "R:/testdata/motherlode/grid/modelDefs/NCEP-GFS-Hawaii_160km.fmrcDefinition.xml",
   "R:/testdata/motherlode/grid/modelDefs/NCEP-GFS-N_Hemisphere_381km.fmrcDefinition.xml",
   "R:/testdata/motherlode/grid/modelDefs/NCEP-GFS-Puerto_Rico_191km.fmrcDefinition.xml",

    "R:/testdata/motherlode/grid/modelDefs/NCEP-NAM-Alaska_22km.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/modelDefs/NCEP-NAM-Alaska_45km-conduit.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/modelDefs/NCEP-NAM-Alaska_45km-noaaport.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/modelDefs/NCEP-NAM-Alaska_95km.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/modelDefs/NCEP-NAM-CONUS_20km-noaaport.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/modelDefs/NCEP-NAM-CONUS_20km-selectsurface.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/modelDefs/NCEP-NAM-CONUS_20km-surface.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/modelDefs/NCEP-NAM-CONUS_40km-conduit.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/modelDefs/NCEP-NAM-CONUS_40km-noaaport.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/modelDefs/NCEP-NAM-CONUS_80km.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/modelDefs/NCEP-NAM-Polar_90km.fmrcDefinition.xml",

    "R:/testdata/motherlode/grid/modelDefs/NCEP-RUC2-CONUS_20km-hybrid.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/modelDefs/NCEP-RUC2-CONUS_20km-pressure.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/modelDefs/NCEP-RUC2-CONUS_20km-surface.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/modelDefs/NCEP-RUC-CONUS_40km.fmrcDefinition.xml", //
    "R:/testdata/motherlode/grid/modelDefs/NCEP-RUC-CONUS_80km.fmrcDefinition.xml",

    "R:/testdata/motherlode/grid/modelDefs/NCEP-DGEX-Alaska_12km.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/modelDefs/NCEP-DGEX-CONUS_12km.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/modelDefs/NCEP-GFS-Global_0p5deg.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/modelDefs/NCEP-NAM-Alaska_11km.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/modelDefs/NCEP-NAM-CONUS_12km.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/modelDefs/NCEP-NDFD-CONUS_5km.fmrcDefinition.xml",
  };

  private static String[] exampleFiles = {
    /* "R:/testdata/motherlode/grid/GFS_Alaska_191km_20060802_1200.grib1", "R:/testdata/motherlode/grid/modelDefs/NCEP-GFS-Alaska_191km.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/GFS_CONUS_80km_20060802_0600.grib1", "R:/testdata/motherlode/grid/modelDefs/NCEP-GFS-CONUS_80km.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/GFS_CONUS_191km_20060802_0000.grib1", "R:/testdata/motherlode/grid/modelDefs/NCEP-GFS-CONUS_191km.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/GFS_CONUS_95km_20060802_0000.grib1", "R:/testdata/motherlode/grid/modelDefs/NCEP-GFS-CONUS_95km.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/GFS_Global_2p5deg_20060801_1200.grib1", "R:/testdata/motherlode/grid/modelDefs/NCEP-GFS-Global_2p5deg.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/GFS_Global_onedeg_20060802_0600.grib1", "R:/testdata/motherlode/grid/modelDefs/NCEP-GFS-Global_onedeg.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/GFS_Hawaii_160km_20060730_0000.grib1", "R:/testdata/motherlode/grid/modelDefs/NCEP-GFS-Hawaii_160km.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/GFS_N_Hemisphere_381km_20060801_0000.grib1", "R:/testdata/motherlode/grid/modelDefs/NCEP-GFS-N_Hemisphere_381km.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/GFS_Puerto_Rico_191km_20060731_0000.grib1", "R:/testdata/motherlode/grid/modelDefs/NCEP-GFS-Puerto_Rico_191km.fmrcDefinition.xml",

    "R:/testdata/motherlode/grid/NAM_Alaska_22km_20060731_1200.grib1", "R:/testdata/motherlode/grid/modelDefs/NCEP-NAM-Alaska_22km.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/NAM_Alaska_45km_conduit_20060801_0000.grib1", "R:/testdata/motherlode/grid/modelDefs/NCEP-NAM-Alaska_45km-conduit.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/NAM_Alaska_45km_noaaport_20060730_0000.grib1", "R:/testdata/motherlode/grid/modelDefs/NCEP-NAM-Alaska_45km-noaaport.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/NAM_Alaska_95km_20060801_0000.grib1", "R:/testdata/motherlode/grid/modelDefs/NCEP-NAM-Alaska_95km.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/NAM_CONUS_20km_noaaport_20060731_0600.grib1", "R:/testdata/motherlode/grid/modelDefs/NCEP-NAM-CONUS_20km-noaaport.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/NAM_CONUS_20km_selectsurface_20060801_0600.grib1", "R:/testdata/motherlode/grid/modelDefs/NCEP-NAM-CONUS_20km-selectsurface.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/NAM_CONUS_20km_surface_20060801_0000.grib1", "R:/testdata/motherlode/grid/modelDefs/NCEP-NAM-CONUS_20km-surface.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/NAM_CONUS_40km_conduit_20060801_0600.grib1", "R:/testdata/motherlode/grid/modelDefs/NCEP-NAM-CONUS_40km-conduit.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/NAM_CONUS_40km_noaaport_20060731_1800.grib1", "R:/testdata/motherlode/grid/modelDefs/NCEP-NAM-CONUS_40km-noaaport.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/NAM_CONUS_80km_20060728_1200.grib1", "R:/testdata/motherlode/grid/modelDefs/NCEP-NAM-CONUS_80km.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/NAM_Polar_90km_20060730_0000.grib1", "R:/testdata/motherlode/grid/modelDefs/NCEP-NAM-Polar_90km.fmrcDefinition.xml",

    "R:/testdata/motherlode/grid/RUC2_CONUS_20km_hybrid_20060802_2100.grib1", "R:/testdata/motherlode/grid/modelDefs/NCEP-RUC2-CONUS_20km-hybrid.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/RUC2_CONUS_20km_pressure_20060802_2000.grib1", "R:/testdata/motherlode/grid/modelDefs/NCEP-RUC2-CONUS_20km-pressure.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/RUC2_CONUS_20km_surface_20060802_1700.grib1", "R:/testdata/motherlode/grid/modelDefs/NCEP-RUC2-CONUS_20km-surface.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/RUC_CONUS_40km_20060802_2000.grib1", "R:/testdata/motherlode/grid/modelDefs/NCEP-RUC-CONUS_40km.fmrcDefinition.xml", //
    "R:/testdata/motherlode/grid/RUC_CONUS_80km_20060802_2000.grib1", "R:/testdata/motherlode/grid/modelDefs/NCEP-RUC-CONUS_80km.fmrcDefinition.xml",

    "R:/testdata/motherlode/grid/DGEX_Alaska_12km_20060731_0000.grib2", "R:/testdata/motherlode/grid/modelDefs/NCEP-DGEX-Alaska_12km.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/DGEX_CONUS_12km_20060730_1800.grib2", "R:/testdata/motherlode/grid/modelDefs/NCEP-DGEX-CONUS_12km.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/GFS_Global_0p5deg_20060726_0600.grib2", "R:/testdata/motherlode/grid/modelDefs/NCEP-GFS-Global_0p5deg.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/NAM_Alaska_11km_20060802_1200.grib2", "R:/testdata/motherlode/grid/modelDefs/NCEP-NAM-Alaska_11km.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/NAM_CONUS_12km_20060801_1200.grib2", "R:/testdata/motherlode/grid/modelDefs/NCEP-NAM-CONUS_12km.fmrcDefinition.xml",
    "R:/testdata/motherlode/grid/NDFD_CONUS_5km_20060731_1200.grib2", "R:/testdata/motherlode/grid/modelDefs/NCEP-NDFD-CONUS_5km.fmrcDefinition.xml",  // */

     "R:/testdata/motherlode/grid/RUC2_CONUS_20km_surface_20060825_1400.grib2", "R:/testdata/motherlode/grid/modelDefs/NCEP-RUC2-CONUS_20km-surface.fmrcDefinition.xml",

  };

  public static void main(String args[]) throws IOException {

    for (int i = 0; i < exampleFiles.length; i+=2)
      convertIds(exampleFiles[i], exampleFiles[i+1]);

    //for (int i = 0; i < defs.length; i+=2)
    //  showVertCoords(defs[i], defs[i+1]);


  }

}
