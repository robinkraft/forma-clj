namespace java forma.schema

struct DoubleArray {
  1: list<double> doubles
}

struct IntArray {
  1: list<i32> ints
}

struct FireTuple {
  2: i32 temp330,
  3: i32 conf50,
  4: i32 bothPreds,
  5: i32 count
}

struct FireSeries {
  1: list<FireTuple> values
}

struct FormaValue {
  1: FireTuple fireValue;
  2: double shortDrop;
  3: double longDrop;
  4: double tStat;
}

struct FormaSeries {
  1: list<FormaValue> values;
}

union DataValue {
  1: i32 intVal
  2: IntArray ints
  3: double doubleVal
  4: DoubleArray doubles  
}

struct DataChunk {
  1: string dataset;
  2: string spatialRes;
  3: string temporalRes = "00";
  4: string tileString;
  5: i32 chunkID;
  6: DataValue chunkValue;
  7: optional string date;
}

struct FormaNeighborValue {
  1: FireTuple fireValue;
  2: i32 numNeighbors;
  3: double avgShortDrop;
  4: double minShortDrop;
  5: double avgLongDrop;
  6: double minLongDrop;
  7: double avgTStat;
  8: double minTStat;
}
