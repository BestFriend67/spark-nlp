class SentenceDetectorDLTestSpec(unittest.TestCase):
    def setUp(self):
        self.data = SparkContextForTest.spark.read.option("header", "true") \
            .csv(path="file:///" + os.getcwd() + "/../src/test/resources/embeddings/sentence_embeddings.csv")

    def runTest(self):
        document_assembler = DocumentAssembler() \
            .setInputCol("text") \
            .setOutputCol("document")

        sentence_detector = SentenceDetectorDLModel.pretrained() \
            .setInputCols(["document"]) \
            .setOutputCol("sentence")

        pipeline = Pipeline(stages=[
            document_assembler,
            sentence_detector
        ])

        model = pipeline.fit(self.data)
        model.transform(self.data).show()

class SentenceDetectorDLExtraParamsTestSpec(unittest.TestCase):
    def runTest(self):
        sampleText = """
            A dog loves going out on a walk, eating and sleeping in front of the fireplace. 
            This how a dog lives. 
            It's great!
        """.strip()
        data_df = SparkContextForTest.spark.createDataFrame([[sampleText]]).toDF("text")

        document_assembler = DocumentAssembler() \
            .setInputCol("text") \
            .setOutputCol("document")

        sentence_detector = SentenceDetectorDLModel.pretrained() \
            .setInputCols(["document"]) \
            .setOutputCol("sentences") \
            .setMaxLength(35) \
            .setMinLength(15) \
            .setCustomBounds([","])

        pipeline = Pipeline(stages=[
            document_assembler,
            sentence_detector
        ])
        model = pipeline.fit(data_df)
        results = model.transform(data_df).selectExpr("explode(sentences)").collect()
        print(results)
        self.assertEqual(len(results), 2)

        sentence_detector \
            .setUseCustomBoundsOnly(True) \
            .setMinLength(0) \
            .setMaxLength(1000) \
            .setCustomBounds([","])

        pipeline = Pipeline(stages=[
            document_assembler,
            sentence_detector
        ])
        model = pipeline.fit(data_df)
        results = model.transform(data_df).selectExpr("explode(sentences)").collect()
        print(results)
        self.assertEqual(len(results), 2)

        impossible_penultimates = sentence_detector.getImpossiblePenultimates()

        sentence_detector \
            .setUseCustomBoundsOnly(False) \
            .setMinLength(0) \
            .setMaxLength(1000) \
            .setCustomBounds([]) \
            .setImpossiblePenultimates(impossible_penultimates + ["fireplace"])

        pipeline = Pipeline(stages=[
            document_assembler,
            sentence_detector
        ])
        model = pipeline.fit(data_df)
        results = model.transform(data_df).selectExpr("explode(sentences)").collect()
        print(results)
        self.assertEqual(len(results), 2)

        sentence_detector \
            .setUseCustomBoundsOnly(False) \
            .setMinLength(0) \
            .setMaxLength(1000) \
            .setCustomBounds([]) \
            .setImpossiblePenultimates(impossible_penultimates)

