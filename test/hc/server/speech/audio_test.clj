(ns hc.server.speech.audio-test
  (:require [clojure.test :refer [deftest is testing]]
            [hc.server.speech.audio :as audio]
            [hc.server.speech.support :as support]))

(deftest wav-duration-is-derived-from-bytes
  (let [bytes (support/wav-bytes 8000 8000)]
    (is (= 1000 (audio/duration-ms bytes "audio/wav")))
    (is (true? (audio/verifiable? bytes "audio/wav"))))
  (testing "unknown containers are not guessed"
    (is (nil? (audio/duration-ms (.getBytes "not audio" "UTF-8")
                                 "audio/webm")))
    (is (false? (audio/verifiable? (.getBytes "not audio" "UTF-8")
                                   "audio/webm")))))

(deftest webm-duration-is-derived-from-the-info-element
  ;; A browser MediaRecorder produces WebM, so a refused WebM duration would
  ;; lock every browser recording out of the cloud path.
  (testing "Duration is read in TimecodeScale units"
    (let [bytes (support/webm-bytes 4200)]
      (is (= 4200 (audio/duration-ms bytes "audio/webm")))
      (is (true? (audio/verifiable? bytes "audio/webm")))))
  (testing "a non-default TimecodeScale is honoured"
    (let [bytes (support/webm-bytes 4200 1000)]
      (is (= 4200 (audio/duration-ms bytes "audio/webm")))))
  (testing "the signature decides, not the declared content type"
    (let [bytes (support/webm-bytes 4200)]
      (is (= 4200 (audio/duration-ms bytes nil)))
      (is (= 4200 (audio/duration-ms bytes "audio/wav")))
      (is (= 4200 (audio/duration-ms bytes "application/octet-stream")))))
  (testing "a header without Segment > Info stays unverifiable"
    (let [header-only (byte-array (map unchecked-byte
                                       (support/ebml-header-bytes)))]
      (is (nil? (audio/duration-ms header-only "audio/webm")))
      (is (false? (audio/verifiable? header-only "audio/webm"))))))
