/**
 *
 */
package io.ddf.content;


import com.google.common.base.Strings;
import io.ddf.DDF;
import io.ddf.exception.DDFException;
import io.ddf.misc.ADDFFunctionalGroupHandler;

import java.util.HashMap;
import java.util.UUID;

public abstract class AMetaDataHandler extends ADDFFunctionalGroupHandler
    implements IHandleMetaData {


  public AMetaDataHandler(DDF theDDF) {
    super(theDDF);
  }

  private UUID mId = UUID.randomUUID();

  @Override
  public UUID getId() {
    return mId;
  }

  @Override
  public void setId(UUID id) {
    mId = id;
  }

  private long mNumRows = 0L;
  private boolean bNumRowsIsValid = false;
  private int useCount = 0;
  /**
   * Each implementation needs to come up with its own way to compute the row
   * count.
   *
   * @return row count of a DDF
   */
  protected abstract long getNumRowsImpl() throws DDFException;

  /**
   * Called to assert that the row count needs to be recomputed at next access
   */

  protected void invalidateNumRows() {
    bNumRowsIsValid = false;
  }

  @Override
  public long getNumRows() throws DDFException {
    if (!bNumRowsIsValid) {
      mNumRows = this.getNumRowsImpl();
      //      bNumRowsIsValid = true;
    }
    return mNumRows;
  }

  @Override
  public boolean inUse() {
    if(useCount > 1) {
      return true;
    } else {
      return false;
    }
  }

  @Override
  public synchronized void increaseUseCount() {
    this.useCount += 1;
  }

  /**
   * Transfer factor information from ddf to this DDF
   * @param ddf
   * @throws DDFException
   */
  public void copyFactor(DDF ddf)  throws DDFException {
    for (Schema.Column col : ddf.getSchema().getColumns()) {
      if (this.getDDF().getColumn(col.getName()) != null && col.getColumnClass() == Schema.ColumnClass.FACTOR) {
        this.getDDF().getSchemaHandler().setAsFactor(col.getName());
      }
    }
    this.getDDF().getSchemaHandler().computeFactorLevelsAndLevelCounts();
  }

  public void copy(IHandleMetaData fromMetaData) throws DDFException {

    this.copyFactor(fromMetaData.getDDF());
  }

  private HashMap<Integer, ICustomMetaData> mCustomMetaDatas;

  public ICustomMetaData getCustomMetaData(int idx) {
    return mCustomMetaDatas.get(idx);
  }

  public void setCustomMetaData(ICustomMetaData customMetaData) {
    mCustomMetaDatas.put(customMetaData.getColumnIndex(), customMetaData);
  }

  public HashMap<Integer, ICustomMetaData> getListCustomMetaData() {
    return mCustomMetaDatas;
  }

  public static interface ICustomMetaData {

    public double[] buildCoding(String value);

    public double get(String value, int idx);

    public int getColumnIndex();
  }

}
