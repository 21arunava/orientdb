/* Generated By:JJTree: Do not edit this line. OBatch.java Version 4.3 */
/* JavaCCOptions:MULTI=true,NODE_USES_PARSER=false,VISITOR=true,TRACK_TOKENS=true,NODE_PREFIX=O,NODE_EXTENDS=,NODE_FACTORY=,SUPPORT_CLASS_VISIBILITY_PUBLIC=true */
package com.orientechnologies.orient.core.sql.parser;

import java.util.Map;

public
class OBatch extends SimpleNode {

  protected OInteger         num;

  protected OInputParameter inputParam;


  public OBatch(int id) {
    super(id);
  }

  public OBatch(OrientSql p, int id) {
    super(p, id);
  }


  /** Accept the visitor. **/
  public Object jjtAccept(OrientSqlVisitor visitor, Object data) {
    return visitor.visit(this, data);
  }


  public void toString(Map<Object, Object> params, StringBuilder builder) {
    if (num == null && inputParam == null) {
      return;
    }

    builder.append(" BATCH ");
    if (num != null) {
      num.toString(params, builder);
    } else {
      inputParam.toString(params, builder);
    }
  }
}
/* JavaCC - OriginalChecksum=b1587460e08cbf21086d8c8fcca192e0 (do not edit this line) */
