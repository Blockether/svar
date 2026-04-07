(fn [s] (= (apply + (map count s)) (count (reduce into #{} s))))
