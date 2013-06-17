package water;

import java.util.Arrays;

import javax.management.RuntimeErrorException;

import water.ValueArray.Column;
import water.api.Constants;

import com.google.gson.JsonObject;

/**
 * A Model models reality (hopefully).
 * A model can be used to 'score' a row, or a collection of rows on any
 * compatible dataset - meaning the row has all the columns with the same names
 * as used to build the mode.
 */
public abstract class Model extends Iced {
  /** Key associated with this Model, if any.  */
  public final Key _selfKey;
  /** Columns used in the model.  No dataset needs to be mapped to this
   *  ValueArray, it is just used to control for valid column data.  The mean &
   *  sigma are from the training dataset listed below, and are used when
   *  normalizing scoring data.  The Column names are used to match up with
   *  scoring data columns.  The last Column is the response column. */
  public final ValueArray _va;

  /** Dataset key used to *build* the model, for models for which this makes
   *  sense, or null otherwise.  Not all models are built from a dataset (eg
   *  artificial models), or are built from a single dataset (various ensemble
   *  models), so this key has no *mathematical* significance in the model but
   *  is handy during common model-building and for the historical record.  */
  public final Key _dataKey;

  /** Empty constructor for deserialization */
  public Model() { _selfKey = null; _va = null; _dataKey = null; }
  /** Default model, built from the selected columns of the given dataset.
   *  Data to be scored on the model has to have all the same columns (in any
   *  order, extra cols are ok).  Last column is the response column, or -1
   *  if there is no defined response column.  */
  public Model( Key key, int cols[], Key dataKey ) {
    _selfKey = key;
    _dataKey = dataKey;
    _va = trimCols((ValueArray)DKV.get(dataKey).get(),cols);
  }
  /** Default artificial model, built from given column names.  */
  public Model( Key key, String[] colNames, String[] classNames ) {
    _selfKey = key;
    _dataKey = null;

    ValueArray.Column Cs[] = new ValueArray.Column[colNames.length+1];
    for( int i=0; i<colNames.length; i++ ) {
      Cs[i] = new ValueArray.Column();
      Cs[i]._name = colNames[i];
      Cs[i]._size = 8;
    }
    ValueArray.Column C = Cs[Cs.length-1] = new ValueArray.Column();
    C._name = Constants.RESPONSE;
    C._domain = classNames;
    C._min = 0.0;
    C._max = classNames==null ? 0 : classNames.length-1;
    _va = new ValueArray(null,0L,8*Cs.length,Cs);
  }
  /** Artificial model.  The 'va' defines the compatible data, but is not
   *  associated with any real dataset.  Data to be scored on the model has to
   *  have all the same columns (in any order, extra cols are ok).  The last
   *  column is the response column.
   */
  public Model( Key key, ValueArray va, Key dataKey ) {
    _selfKey = key;
    _va = va;
    _dataKey = dataKey;
  }
  /** Simple shallow copy constructor */
  public Model( Key key, Model m ) { this(key,m._va,m._dataKey); }

  // Build a new VA from the existing one, removing all columns being ignored,
  // or having constant value (removing constant value columns speeds up
  // modeling since these columns will not help), or otherwise failing a
  // model-specific filter.  The response column is last, and is optional - (an
  // optional one will be filled in with sane defaults).
  private ValueArray trimCols( ValueArray ary, int[] cols ) {
    ValueArray.Column Cs[] = new ValueArray.Column[cols.length];
    int idx = 0;
    int rowsize=0;
    for( int i=0; i<cols.length-1; i++ ) {
      int col = cols[i];        // Gather selected columns
      ValueArray.Column C = ary._cols[col];
      if( C._max != C._min &&   // Trim out constant columns
          columnFilter(C) ) {   // Model-specific column trimmer function
        Cs[idx++] = C;
        rowsize += Math.abs(C._size);
      }
    }
    int resp = cols[cols.length-1]; // Response column, or -1
    Cs[idx++] = resp == -1 ? new ValueArray.Column() : ary._cols[resp];
    rowsize += Math.abs(Cs[idx-1]._size);
    if( idx < Cs.length ) Cs = Arrays.copyOf(Cs,idx); // Trim if constant columns
    return new ValueArray(ary._key,0,rowsize,Cs);
  }

  // True if the column should be accepted.
  public boolean columnFilter(ValueArray.Column C) { return true; }

  /** Called when deleting this model, to cleanup any internal keys */
  public void delete() { }

  /** Response column info */
  public final ValueArray.Column response() { return _va._cols[_va._cols.length-1]; }
  public final String responseName() { return response()._name; }

  // For any given dataset to be scored, we build a mapping from the model's
  // columns to the dataset's columns.  This mapping is a int[] unique to the
  // combo of model & dataset, and has limited internal assertions.  i.e., if
  // you hand this API a junk mapping, it will crash.
  // Example:
  //  Model has columns: ID, AGE, SEX, YEAR, WEIGHT
  //  Dataset has columns: 0:ID, 1:NAME, 2:AGE, 3:WEIGHT, 4:SEX, 5:MONTH, 6:YEAR
  //  Then the mapping is: int[]{0,2,4,6,3}

  private static int find(String n, String[] names) {
    if( n == null ) return -1;
    for( int j = 0; j<names.length; j++ )
      if( n.equals(names[j]) )
        return j;
    return -1;
  }

  /** Map from the model's columns to the given column names, or to -1 if no
   *  column name maps.  Last entry is a mapping for the response Name.  Return
   *  results range from -1 to number-of-columns in the dataset/names[] (which
   *  may be larger than the model).  */
  public final int[] columnMapping( String[] names ) {
    int mapping[] = new int[_va._cols.length];
    for( int i = 0; i<mapping.length; i++ )
      mapping[i] = find(_va._cols[i]._name,names);
    return mapping;
  }

  /** Check that this is the identity map */
  public static boolean identityMap( int[] mapping ) {
    if( mapping == null ) return true;
    for( int i=0; i<mapping.length; i++ )
      if( mapping[i] != i )
        return false;
    return true;
  }

  /** Check if this mapping is compatible.  Just means no -1 entries in the
   *  predictor variables (response column is not checked).  */
  public static boolean isCompatible( int[] mapping ) {
    if( mapping == null ) return true;
    for( int i=0; i<mapping.length-1; i++ )
      if( mapping[i] == -1 )    // No mapping?
        return false;           // Fail
    return true;
  }

  /** Check if this model is compatible with this collection of column names. */
  public final boolean isCompatible( String[] names ) {
    throw H2O.unimpl();
    //return isCompatible(columnMapping(names));
  }

  /** Check if this dataset is compatible with this model.  All the columns in
   *  the model have to be present, but extra columns may exist and the columns
   *  may be in a different order.
   */
  public final boolean isCompatible( ValueArray data ) {
    throw H2O.unimpl();
    //return isCompatible(data.colNames());
  }

  /**
   * Simple model wrapper adapting original model to different dataset.
   *
   * Basically does column and categorical mapping. Each row (irrespectfull of its source) is first
   * loaded into internal array which is permuted to match the original column order.
   * Categorical values are mapped to the values corresponding strings had in original dataset or NaN if we did not see this value before.
   *
   * @author tomasnykodym
   *
   */
  private static class ModelDataAdaptor extends Model {
    final Model M;
    final int       _yCol;
    final int  []   _xCols;
    final int  [][] _catMap;
    final double [] _row;

    public ModelDataAdaptor(Model M, int yCol, int [] cols, int [][] catMap){
      this.M = M;
      _row = MemoryManager.malloc8d(cols.length);
      _xCols = cols;
      _catMap = catMap;
      _yCol = yCol;
    }
    private final double translateCat(int col, int val){
      int res = _catMap[col][val];
      return res == -1?Double.NaN:res;
    }
    private final double translateCat(int col, double val){
      if(Double.isNaN(val))return val;
      assert val == (int)val;
      return translateCat(col, (int)val);
    }

    @Override public final double score(double[] data) {
      int j = 0;
      for(int i = 0; i < _xCols.length; ++i)
        _row[j++] = (_catMap == null || _catMap[i] == null)?data[_xCols[i]]:translateCat(i, data[_xCols[i]]);
      return M.score0(_row);
    }

    @Override public double score(ValueArray data, AutoBuffer ab, int row) {
      int j = 0;
      for(int i = 0; i < _xCols.length; ++i)
        _row[j++] = (_catMap == null || _catMap[i] == null)
          ?data.datad(ab,row, _xCols[i])
              :translateCat(i,(int)data.data(ab,row, _xCols[i]));
      return M.score0(_row);
    }
    // always should call directly M.score0...
    @Override protected final double score0(double[] data) {
      throw new RuntimeException("should NEVER be called!");
    }
    @Override public JsonObject toJson() {return M.toJson();}
    // keep only one adaptor layer! (just in case there would be multiple adapt calls...)
    @Override public final Model adapt(ValueArray ary){return M.adapt(ary);}
    @Override public final Model adapt(String [] cols){return M.adapt(cols);}
  }

  /**
   * Adapt model for the given dataset.
   * Default behavior is to map columns and categoricals to their original indexes.
   * Categorical values we have not seen when building the model are translated as NaN.
   *
   * Override this to get custom adapt behavior (eg. handle unseen cats differently).
   *
   * @param ary - tst dataset
   * @return Model - model adapted to be applied on the given data
   */
  public Model adapt(ValueArray ary){
    boolean id = true;
    final int  [] colMap = columnMapping(ary.colNames());
    if(!isCompatible(colMap))throw new IllegalArgumentException("This model uses different columns than those provided");
    final int[][] catMap =  new int[colMap.length][];
    for(int i = 0; i < colMap.length-1; ++i){
      Column c = ary._cols[colMap[i]];
      if(c.isEnum() && !Arrays.deepEquals(_va._cols[i]._domain, c._domain)){
        id = false;
        catMap[i] = new int[c._domain.length];
        for(int j = 0; j < c._domain.length; ++j)
          catMap[i][j] = find(c._domain[j],_va._cols[i]._domain);
      }
    }
    return (id&&identityMap(colMap))?this:new ModelDataAdaptor(this,colMap[colMap.length-1],Arrays.copyOf(colMap,colMap.length-1),catMap);
  }
  /**
   * Adapt model for given columns.
   * Only permutes the columns by the column names (factor levels MUST match the training dataset).
   * @param colNames
   * @return
   */
  public Model adapt(String [] colNames){
    final int [] colMap = columnMapping(colNames);
    if(!isCompatible(colMap))throw new IllegalArgumentException("This model uses different columns than those provided");
    if(identityMap(colMap))return this;
    return new ModelDataAdaptor(this, colMap[colMap.length-1],Arrays.copyOf(colMap,colMap.length-1), null);
  }
  public double score(double [] data){
    return score0(data);
  }
  public double score(ValueArray ary,AutoBuffer bits, int rid){
    throw new RuntimeException("model should be first adapted to new dataset!");
  }



  // Subclasses implement the scoring logic.  They can assume all datasets are
  // compatible already
  protected abstract double score0(double [] data);


  /** Single row scoring, on a compatible ValueArray (when pushed throw the mapping) */
  protected double score0( ValueArray data, int row){
    throw new RuntimeException("Should never be called on non-adapted model. Call Model.adapt(ValueArray) first!");
  }

  /** Bulk scoring API, on a compatible ValueArray (when pushed throw the mapping) */
  protected double score0( ValueArray data, AutoBuffer ab, int row_in_chunk){
    throw new RuntimeException("Should never be called on non-adapted model. Call Model.adapt(ValueArray) first!");
  }

  public abstract JsonObject toJson();

  public void fromJson(JsonObject json) {
    // TODO
  }

}
