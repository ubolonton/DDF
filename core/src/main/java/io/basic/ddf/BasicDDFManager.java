/**
 *
 */
package io.basic.ddf;


import io.ddf.DDF;
import io.ddf.DDFManager;
import io.ddf.content.Schema;
import io.ddf.exception.DDFException;

import java.util.List;
import java.util.UUID;

/**
 * An implementation of DDFManager with local memory and local storage
 */
public class BasicDDFManager extends DDFManager {

  @Override
  public String getEngine() {
    return "basic";
  }


  @Override
  public DDF transfer(String fromEngine, String ddfuri) {
    return null;
  }

  public BasicDDFManager() {
  }


  public <T> DDF newDDF(List<T> rows, Class<T> unitType, String
          engineName, String namespace, String name, Schema schema)
      throws DDFException {

    if (rows == null || rows.isEmpty()) {
      throw new DDFException("Non-null/zero-length List is required to instantiate a new BasicDDF");
    }

    return this.newDDF(this, rows, new Class[] { List.class, unitType },
            engineName, namespace, name, schema);
  }

  public DDF loadTable(String fileURL, String fieldSeparator) throws DDFException {
    throw new DDFException("Load DDF from file is not supported!");
  }

  @Override
  public DDF getOrRestoreDDFUri(String ddfURI) throws DDFException {
    return null;
  }

  @Override
  public DDF getOrRestoreDDF(UUID uuid) throws DDFException {
    return null;
  }
}
