package luau.panama

object LxConstants:
  val LX_RESUME_OK     = 0
  val LX_RESUME_YIELD  = 1
  val LX_RESUME_ERR    = 2
  val LX_RESUME_MEMERR = 3

  val LX_RETURN  = 0
  val LX_FAIL    = 1
  val LX_SUSPEND = 2

  val LX_TNONE     = -1
  val LX_TNIL      = 0
  val LX_TBOOLEAN  = 1
  val LX_TNUMBER   = 3
  val LX_TINTEGER  = 4
  val LX_TVECTOR   = 5
  val LX_TSTRING   = 6
  val LX_TTABLE    = 7
  val LX_TFUNCTION = 8
  val LX_TUSERDATA = 9
  val LX_TTHREAD   = 10
  val LX_TBUFFER   = 11
