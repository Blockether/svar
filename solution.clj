(fn [board color]
  (let [opp ({'w 'b 'b 'w} color)
        n (count board)
        dirs '[[-1 -1] [-1 0] [-1 1] [0 -1] [0 1] [1 -1] [1 0] [1 1]]
        cell (fn [r c] (get-in board [r c]))
        flip-dir (fn [r c [dr dc]]
                   (loop [r (+ r dr) c (+ c dc) acc []]
                     (if-not (and (<= 0 r (dec n)) (<= 0 c (dec n)))
                       nil
                       (case (cell r c)
                         opp (recur (+ r dr) (+ c dc) (conj acc [r c]))
                         color (when (seq acc) (set acc))
                         nil))))
        move-flips (fn [r c]
                     (when (= (cell r c) 'e)
                       (let [fs (keep #(flip-dir r c %) dirs)]
                         (when (seq fs) (into #{} (mapcat identity fs))))))]
    (into {} (for [r (range n) c (range n)
                   :let [f (move-flips r c)]
                   :when f]
               [[r c] f]))))
