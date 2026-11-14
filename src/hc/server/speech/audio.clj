(ns hc.server.speech.audio
  "Small, dependency-free audio metadata checks.

  The server must not trust a client-supplied duration for the five-minute
  voice-input cap.  Two containers carry enough information to compute the
  duration from the bytes themselves: RIFF/WAVE and EBML/WebM (what a
  browser MediaRecorder produces).  Anything else returns nil, and the
  speech service then decides whether the selected provider may receive an
  unverifiable recording.

  This namespace does not decode or play audio.")

(defn- ascii
  [^bytes bytes offset length]
  (String. bytes offset length "US-ASCII"))

(defn- u16
  [^bytes bytes offset]
  (bit-or (bit-and (int (aget bytes offset)) 0xff)
          (bit-shift-left (bit-and (int (aget bytes (inc offset))) 0xff) 8)))

(defn- u32
  [^bytes bytes offset]
  (bit-or (bit-and (int (aget bytes offset)) 0xff)
          (bit-shift-left (bit-and (int (aget bytes (inc offset))) 0xff) 8)
          (bit-shift-left (bit-and (int (aget bytes (+ offset 2))) 0xff) 16)
          (bit-shift-left (bit-and (int (aget bytes (+ offset 3))) 0xff) 24)))

(defn- wav-duration-ms
  [^bytes bytes]
  (when (and (>= (alength bytes) 44)
             (= "RIFF" (ascii bytes 0 4))
             (= "WAVE" (ascii bytes 8 4)))
    (loop [offset 12
           byte-rate nil
           data-size nil]
      (if (> (+ offset 8) (alength bytes))
        (when (and byte-rate data-size (pos? (long byte-rate)))
          (long (Math/round (* 1000.0 (/ (double data-size)
                                         (double byte-rate))))))
        (let [id (ascii bytes offset 4)
              size (u32 bytes (+ offset 4))
              body (+ offset 8)
              next-offset (+ body size (bit-and size 1))]
          (cond
            (and (= "fmt " id) (>= (+ body 12) (alength bytes)))
            nil

            (= "fmt " id)
            (recur next-offset (u32 bytes (+ body 8)) data-size)

            (and (= "data" id) (<= (+ body size) (alength bytes)))
            (recur next-offset byte-rate size)

            (<= next-offset (alength bytes))
            (recur next-offset byte-rate data-size)

            :else
            (when (and byte-rate data-size (pos? (long byte-rate)))
              (long (Math/round (* 1000.0 (/ (double data-size)
                                             (double byte-rate))))))))))))


;; ---------------------------------------------------------------------------
;; EBML / WebM
;;
;; A browser MediaRecorder produces WebM.  Its Segment > Info element carries
;; TimecodeScale (nanoseconds per timecode unit) and Duration (timecode units),
;; so the recording length is verifiable from the bytes exactly as a WAV
;; header's is.  Without this the cloud path refuses every browser recording,
;; which is a capability gap rather than a safety property.

(def ^:private ebml-header-id 0x1A45DFA3)
(def ^:private segment-id 0x18538067)
(def ^:private info-id 0x1549A966)
(def ^:private timecode-scale-id 0x2AD7B1)
(def ^:private duration-id 0x4489)
(def ^:private default-timecode-scale 1000000)

(defn- leading-bit-length
  "Length in bytes of the variable-length field whose first byte is FIRST-BYTE."
  [first-byte]
  (loop [n 1 mask 0x80]
    (cond
      (zero? (int first-byte)) nil
      (pos? (bit-and (int first-byte) mask)) (when (<= n 8) n)
      (>= n 8) nil
      :else (recur (inc n) (bit-shift-right mask 1)))))

(defn- ebml-id
  "Numeric ID of the element at OFFSET, or nil.

  An EBML ID keeps its marker bit, so the value is read whole."
  [^bytes bytes offset limit]
  (when (< offset limit)
    (when-let [n (leading-bit-length (aget bytes offset))]
      (when (<= (+ offset n) limit)
        (loop [i 0 acc 0]
          (if (< i n)
            (recur (inc i)
                   (bit-or (bit-shift-left acc 8)
                           (bit-and (int (aget bytes (+ offset i))) 0xff)))
            [acc n]))))))

(defn- ebml-size
  "`[value length]` for the data size at OFFSET, or nil for unknown size."
  [^bytes bytes offset limit]
  (when (< offset limit)
    (when-let [n (leading-bit-length (aget bytes offset))]
      (when (<= (+ offset n) limit)
        (let [first-byte (bit-and (int (aget bytes offset)) 0xff)
              mask (bit-shift-left 1 (- 8 n))
              value (loop [i 0 acc (bit-and first-byte (dec mask))]
                      (if (< i (dec n))
                        (recur (inc i)
                               (bit-or (bit-shift-left acc 8)
                                       (bit-and (int (aget bytes (+ offset 1 i)))
                                                0xff)))
                        acc))
              all-ones (dec (bit-shift-left 1 (* 7 n)))]
          (when (not= value all-ones)
            [value n]))))))

(defn- ebml-uint
  "Big-endian unsigned integer of LENGTH bytes at OFFSET."
  [^bytes bytes offset length]
  (loop [i 0 acc 0]
    (if (< i length)
      (recur (inc i)
             (bit-or (bit-shift-left acc 8)
                     (bit-and (int (aget bytes (+ offset i))) 0xff)))
      acc)))

(defn- ebml-float
  "Big-endian IEEE-754 float of LENGTH bytes at OFFSET, or nil."
  [^bytes bytes offset length]
  (case length
    4 (Float/intBitsToFloat (int (ebml-uint bytes offset 4)))
    8 (Double/longBitsToDouble (long (ebml-uint bytes offset 8)))
    nil))

(defn- children
  "Lazily walk the EBML children of [OFFSET LIMIT).

  Yields `[id body-start body-end]` for each element whose header is complete.
  A zero-length element yields an empty range; an unknown-size element stops
  the walk, because skipping it would need its content to be parsed, and this
  namespace only needs Info."
  [^bytes bytes offset limit]
  (loop [offset offset]
    (lazy-seq
     (when (< offset limit)
       (when-let [id (ebml-id bytes offset limit)]
         (let [id-len (second id)
               head (+ offset id-len)]
           (when-let [size (ebml-size bytes head limit)]
             (let [[size-value size-len] size
                   body (+ head size-len)
                   body-end (min limit (+ body size-value))]
               (when (<= body limit)
                 (cons [(first id) body body-end]
                       (children bytes body-end limit)))))))))))

(defn- info-duration-ms
  "Duration in milliseconds from a Segment > Info range, or nil."
  [^bytes bytes body limit]
  (let [fields (reduce (fn [acc [id start end]]
                         (cond
                           (= id timecode-scale-id)
                           (assoc acc :scale (ebml-uint bytes start (- end start)))

                           (= id duration-id)
                           (assoc acc :duration (ebml-float bytes start (- end start)))

                           :else acc))
                       {}
                       (children bytes body limit))
        duration (:duration fields)
        scale (or (:scale fields) default-timecode-scale)]
    (when (and (number? duration)
               (pos? (double duration))
               (pos? (long scale)))
      ;; Duration is in TimecodeScale units; TimecodeScale is nanoseconds per
      ;; unit.  Divide only once, at the end, so the arithmetic stays exact.
      (long (Math/round (* (double duration) (double scale) 1e-6))))))


(defn- ebml-signature?
  [^bytes bytes]
  (when (and bytes (>= (alength bytes) 4))
    (boolean (some-> (ebml-id bytes 0 (alength bytes)) first (= ebml-header-id)))))

(defn- webm-duration-ms
  "Duration in milliseconds from a WebM/EBML byte array, or nil.

  Only Segment > Info is read.  Segment is a sibling of the EBML header, not
  a child of it, so this walks the top level looking for it.  A live stream
  without a Duration element simply has no verifiable length and yields nil;
  guessing from the last cluster would be a different, weaker claim."
  [^bytes bytes]
  (when (ebml-signature? bytes)
    (let [limit (alength bytes)]
      (some (fn [[id start end]]
              (when (= id segment-id)
                (some (fn [[child-id child-start child-end]]
                        (when (= child-id info-id)
                          (info-duration-ms bytes child-start child-end)))
                      (children bytes start end))))
            (children bytes 0 limit)))))

(defn duration-ms
  "Return the duration of BYTES in milliseconds when it is verifiable from
  the container, or nil when this namespace cannot prove it.

  `content-type` is accepted for call-site symmetry and is deliberately not
  consulted: the container signature is authoritative, so a mislabelled
  payload cannot talk this function into trusting a header the bytes do not
  actually carry."
  [^bytes bytes _content-type]
  (when (and bytes (pos? (alength bytes)))
    (cond
      (and (>= (alength bytes) 4) (= "RIFF" (ascii bytes 0 4)))
      (wav-duration-ms bytes)

      (ebml-signature? bytes)
      (webm-duration-ms bytes)

      :else nil)))

(defn verifiable?
  [bytes content-type]
  (some? (duration-ms bytes content-type)))
