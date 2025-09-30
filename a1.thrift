exception IllegalArgument {
  1: string message;
}

// BE 服务器接口 - BE 实现这个接口
service MiningPoolService {
  i64 mineBlock (1: i32 version, 2: binary prevBlockHash, 3: binary merkleRootHash, 4: i64 time, 5: i64 target) throws (1: IllegalArgument e);
  void cancel();
  void registerBE (1: string host, 2: i32 port, 3: i32 numCores) throws (1: IllegalArgument e);
  i64 mineBlockInRange (1: i32 version, 2: binary prevBlockHash, 3: binary merkleRootHash, 4: i64 time, 5: i64 target, 6: i64 startNonce, 7: i64 endNonce, 8: i32 numThreads) throws (1: IllegalArgument e);
}
