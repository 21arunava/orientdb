/* Generated By:JJTree: Do not edit this line. OTruncateRecordStatement.java Version 4.3 */
/* JavaCCOptions:MULTI=true,NODE_USES_PARSER=false,VISITOR=true,TRACK_TOKENS=true,NODE_PREFIX=O,NODE_EXTENDS=,NODE_FACTORY=,SUPPORT_CLASS_VISIBILITY_PUBLIC=true */
package com.orientechnologies.orient.core.sql.parser;

import java.util.List;
import java.util.Map;

public class OTruncateRecordStatement extends OStatement {
  protected ORid       record;
  protected List<ORid> records;

  public OTruncateRecordStatement(int id) {
    super(id);
  }

  public OTruncateRecordStatement(OrientSql p, int id) {
    super(p, id);
  }

  @Override
  public void toString(Map<Object, Object> params, StringBuilder builder) {
    builder.append("TRUNCATE RECORD ");
    if (record != null) {
      record.toString(params, builder);
    } else {
      builder.append("[");
      boolean first = true;
      for (ORid r : records) {
        if (!first) {
          builder.append(",");
        }
        r.toString(params, builder);
        first = false;
      }
      builder.append("]");
    }
  }
}
/* JavaCC - OriginalChecksum=9da68e9fe4c4bf94a12d8a6f8864097a (do not edit this line) */
